package org.ccnx.ccn.profiles.access;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.LinkAuthenticator;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.io.content.WrappedKey;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;


/**
 * Wrapper for Group public key, and a way to access its private keys.
 * Model for private key access: if you're not allowed to get a key,
 * we throw AccessDeniedException.
 * @author smetters
 *
 */
public class Group {
	
	// Right now dynamically load both public key and membership list.
	// For efficiency might want to only load public key, and pull membership
	// list only when we need to.
	private ContentName _groupNamespace;
	private PublicKeyObject _groupPublicKey;
	private MembershipList _groupMembers; 
	private String _groupFriendlyName;
	private CCNHandle _library;
	private GroupManager _groupManager;
	
	private KeyDirectory _privKeyDirectory = null;
	
	public Group(ContentName namespace, String groupFriendlyName, CCNHandle library,GroupManager manager) throws IOException, ConfigurationException, XMLStreamException {
		_library = library;
		_groupNamespace = namespace;
		_groupFriendlyName = groupFriendlyName;
		_groupPublicKey = new PublicKeyObject(AccessControlProfile.groupPublicKeyName(_groupNamespace, _groupFriendlyName), _library);
		_groupPublicKey.updateInBackground(true);
		_groupManager = manager;
	}
	
	public Group(ContentName groupName, CCNHandle library,GroupManager manager) throws IOException, ConfigurationException, XMLStreamException {
		this(groupName.parent(), AccessControlProfile.groupNameToFriendlyName(groupName), library,manager);
	}
	
	/**
	 * Package constructor.
	 * @return
	 */
	Group(ContentName namespace, String groupFriendlyName, MembershipList members, 
		  PublicKeyObject publicKey, CCNHandle library,GroupManager manager) {
		_library = library;
		_groupNamespace = namespace;
		_groupFriendlyName = groupFriendlyName;
		_groupMembers = members;
		_groupPublicKey = publicKey;
		_groupManager = manager;
	}
	
	/**
	 * Constructor that creates a new group and generates a first key pair for it.
	 * @return
	 * @throws ConfigurationException 
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws InvalidKeyException 
	 */
	Group(ContentName namespace, String groupFriendlyName, MembershipList members, 
					CCNHandle library, GroupManager manager) throws XMLStreamException, IOException, ConfigurationException, InvalidKeyException {		
		this(namespace, groupFriendlyName, members, null, library,manager);
//		_groupPublicKey = new PublicKeyObject(AccessControlProfile.groupPublicKeyName(_groupNamespace, _groupFriendlyName), _library);
		createGroupPublicKey(manager, members);
		_groupMembers.saveToRepository();
	}
	
	
	public void addUsers(ArrayList<Link> newUsers)
			throws XMLStreamException, IOException, InvalidKeyException,
			InvalidCipherTextException, AccessDeniedException,
			ConfigurationException {
		modify(newUsers, null);						
	}

	public void removeUsers( ArrayList<Link> removedUsers) throws XMLStreamException,
			IOException, InvalidKeyException, InvalidCipherTextException,
			AccessDeniedException, ConfigurationException {
		modify(null, removedUsers);
	}

	public boolean ready() {
		return _groupPublicKey.available();
	}
	
	public KeyDirectory privateKeyDirectory(AccessControlManager manager) throws IOException {
		if (_privKeyDirectory != null) {
			return _privKeyDirectory;
		}
		if (_groupPublicKey.available()) {
			_privKeyDirectory = new KeyDirectory(manager, 
					AccessControlProfile.groupPrivateKeyDirectory(_groupPublicKey.getVersionedName()), _library);
			return _privKeyDirectory;
		}
		Log.info("Public key not ready for group: " + friendlyName());
		return null;
	}
	
	protected void stopPrivateKeyDirectoryEnumeration() throws IOException{
		if(_privKeyDirectory != null){
			_privKeyDirectory.stopEnumerating();
		}
	}
	
	public void restartPrivateKeyDirectoryEnumeration(AccessControlManager manager) throws IOException{
		stopPrivateKeyDirectoryEnumeration();
		_privKeyDirectory = null;
		privateKeyDirectory(manager);
	}
	
	public String friendlyName() { return _groupFriendlyName; }

	/**
	 * Returns a list containing all the members of a Group object
	 * Sets up the list to automatically update in the background
	 * 
	 * @return MembershipList- a list containing all the members of a Group object
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws ConfigurationException
	 */
	public MembershipList membershipList() throws XMLStreamException, IOException, ConfigurationException { 
		if (null == _groupMembers) {
			// Read constructor. Synchronously updates. 
			// Elaine: the code will throw an exception if no membership list is found or upon error
			// reading membership list from network... need error handling...
			_groupMembers = new MembershipList(AccessControlProfile.groupMembershipListName(_groupNamespace, _groupFriendlyName), _library);
			// Keep dynamically updating.
			_groupMembers.updateInBackground(true);
		}
		return _groupMembers; 
	}
	
	public ContentName membershipListName() throws XMLStreamException, IOException, ConfigurationException { 
		return membershipList().getVersionedName(); 
	}
	
	public CCNTime membershipListVersion() throws XMLStreamException, IOException, ConfigurationException {
		ContentName name = membershipListName();
		if (VersioningProfile.hasTerminalVersion(name)) {
			try {
				return VersioningProfile.getLastVersionAsTimestamp(name);
			} catch (VersionMissingException e) {
				Log.warning("Should not happen: VersionMissingException on name where isVersioned is true: " + name + ": " + e.getMessage());
			}
		}
		return null;
	}
	
	/**
	 * This does not actually remove any members from the group, it just
	 * clears out our in-memory copy of the membership list.
	 */
	public void clearCachedMembershipList() {
		if (null != _groupMembers) {
			_groupMembers.cancelInterest(); // stop updating
			_groupMembers = null;
		}
	}

	PublicKeyObject publicKeyObject() { return _groupPublicKey; }
	
	public PublicKey publicKey() throws ContentNotReadyException, ContentGoneException { return _groupPublicKey.publicKey(); }
	
	public ContentName publicKeyName() { 
		return _groupPublicKey.getVersionedName();
	}
	
	public CCNTime publicKeyVersion() throws IOException {
		return _groupPublicKey.getVersion();
	}

	public void setMembershipList(GroupManager groupManager, java.util.Collection<Link> newMembers) 
					throws XMLStreamException, IOException, 
						InvalidKeyException, InvalidCipherTextException, AccessDeniedException, ConfigurationException {
		// need to figure out if we need to know private key; if we do and we don't, throw access denied.
		// We're deleting anyone that exists
		this._groupManager = groupManager;
		MembershipList ml = membershipList(); // force retrieval if haven't already.
		if (ml.available() && !ml.isGone() && (ml.membershipList().contents().size() > 0)) {
			modify(newMembers, ml.membershipList().contents());
		} else {
			modify(newMembers, null);
		}
	}
	
	public void newGroupPublicKey(GroupManager manager, MembershipList ml) throws AccessDeniedException, IOException, XMLStreamException, InvalidKeyException, InvalidCipherTextException, ConfigurationException {
		KeyDirectory oldPrivateKeyDirectory = privateKeyDirectory(manager.getAccessManager());
		Key oldPrivateKeyWrappingKey = oldPrivateKeyDirectory.getUnwrappedKey(null);
		if (null == oldPrivateKeyWrappingKey) {
			throw new AccessDeniedException("Cannot update group membership, do not have acces rights to private key for group " + friendlyName());
		} 
		
		// Generate key pair
		// Write public key to new versioned name
		// Open key directory under that name
		// Wrap private key in wrapping key, write that block
		// For each principal on membership list, write wrapped key block
		Key privateKeyWrappingKey = createGroupPublicKey(manager, ml);
		
		// Write superseded block in old key directory
		oldPrivateKeyDirectory.addSupersededByBlock(oldPrivateKeyWrappingKey, publicKeyName(), privateKeyWrappingKey);
		// Write link back to previous key
		Link lr = new Link(_groupPublicKey.getVersionedName(), new LinkAuthenticator(new PublisherID(KeyManager.getKeyManager().getDefaultKeyID())));
		LinkObject precededByBlock = new LinkObject(KeyDirectory.getPreviousKeyBlockName(publicKeyName()), lr, _library);
		precededByBlock.saveToRepository();
	}
	
	/**
	 * We don't expect there to be an existing key. So we just write it.
	 * If we're not supposed to be a member, this is tricky... we just live
	 * with the fact that we know it, and forget it.
	 * @param ml
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws ConfigurationException 
	 * @throws InvalidKeyException 
	 * @throws InvalidKeyException 
	 */
	public Key createGroupPublicKey(GroupManager manager, MembershipList ml) 
			throws XMLStreamException, IOException, ConfigurationException, InvalidKeyException {
		
		KeyPairGenerator kpg = null;
		try {
			kpg = KeyPairGenerator.getInstance(manager.getGroupKeyAlgorithm());
		} catch (NoSuchAlgorithmException e) {
			if (manager.getGroupKeyAlgorithm().equals(AccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM)) {
				Log.severe("Cannot find default group public key algorithm: " + AccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM + ": " + e.getMessage());
				throw new RuntimeException("Cannot find default group public key algorithm: " + AccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM + ": " + e.getMessage());
			}
			throw new ConfigurationException("Specified group public key algorithm " + manager.getGroupKeyAlgorithm() + " not found. " + e.getMessage());
		}
		kpg.initialize(AccessControlManager.DEFAULT_GROUP_KEY_LENGTH);
		KeyPair pair = kpg.generateKeyPair();
		
		_groupPublicKey = 
			new PublicKeyObject(
					AccessControlProfile.groupPublicKeyName(_groupNamespace, _groupFriendlyName), 
					pair.getPublic(),
					_library);
		_groupPublicKey.saveToRepository();
		
		stopPrivateKeyDirectoryEnumeration();
		_privKeyDirectory = null;
		
		KeyDirectory newPrivateKeyDirectory = privateKeyDirectory(manager.getAccessManager()); // takes from new public key
		
		Key privateKeyWrappingKey = WrappedKey.generateNonceKey();
		
		try {
			// write the private key
			newPrivateKeyDirectory.addPrivateKeyBlock(pair.getPrivate(), privateKeyWrappingKey);
		} catch (InvalidKeyException e) {
			Log.warning("Unexpected -- InvalidKeyException wrapping key with keys we just generated! " + e.getMessage());
			throw e;
		}
		
		PublicKeyObject latestPublicKey = null;
		for (Link lr : ml.membershipList().contents()) {
			try {
				// DKS TODO verify target public key against publisher, etc in link
				ContentName pkName = lr.targetName();
				if (manager.isGroup(lr)){
					pkName = AccessControlProfile.groupPublicKeyName(pkName);
				}
				System.out.println("retrieving pub key from:..." + pkName);
				
				latestPublicKey = new PublicKeyObject(pkName, _library);
				if (!latestPublicKey.available()) {
					Log.warning("Could not retrieve public key for " + pkName);
					continue;
				}
				// Need to write wrapped key block and linking principal name.
				newPrivateKeyDirectory.addWrappedKeyBlock(
						privateKeyWrappingKey, 
						latestPublicKey.getVersionedName(), 
						latestPublicKey.publicKey());
			} catch (XMLStreamException e) {
				Log.warning("Could not retrieve public key for principal " + lr.targetName() + ", skipping.");
			} catch (VersionMissingException e) {
				Log.warning("Unexpected: public key name not versioned! " + latestPublicKey.getVersionedName() + ", unable to retrieve principal's public key. Skipping.");
			}
		}
		return privateKeyWrappingKey;
		
	}
	
	/**
	 * We need to wrap the group public key wrapping key in the latest public
	 * keys of the members to add.
	 * @param membersToAdd
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 * @throws AccessDeniedException if we can't get the private key to rewrap. 
	 * 		TODO also check write access list.
	 * @throws ConfigurationException 
	 */
	public void updateGroupPublicKey(GroupManager manager, 
									 java.util.Collection<Link> membersToAdd) 
				throws IOException, InvalidKeyException, InvalidCipherTextException, XMLStreamException, AccessDeniedException, ConfigurationException {
		
		if ((null == membersToAdd) || (membersToAdd.size() == 0))
			return;
		
		KeyDirectory privateKeyDirectory = privateKeyDirectory(manager.getAccessManager());
		Key privateKeyWrappingKey = privateKeyDirectory.getUnwrappedKey(null);
		if (null == privateKeyWrappingKey) {
			throw new AccessDeniedException("Cannot update group membership, do not have acces rights to private key for group " + friendlyName());
		} 
		
		PublicKeyObject latestPublicKey = null;
		for (Link lr : membersToAdd) {
			try {
				// DKS TODO verify target public key against publisher, etc in link
				latestPublicKey = new PublicKeyObject(lr.targetName(), _library);
				if (!latestPublicKey.available()) {
					Log.warning("Could not retrieve public key for " + lr.targetName());
					continue;
				}
				// Need to write wrapped key block and linking principal name.
				privateKeyDirectory.addWrappedKeyBlock(
						privateKeyWrappingKey, 
						latestPublicKey.getVersionedName(), 
						latestPublicKey.publicKey());
			} catch (XMLStreamException e) {
				Log.warning("Could not retrieve public key for principal " + lr.targetName() + ", skipping.");
			} catch (VersionMissingException e) {
				Log.warning("Unexpected: public key name not versioned! " + latestPublicKey.getVersionedName() + ", unable to retrieve principal's public key. Skipping.");
			}
		}
	}
	
	@Override
	public String toString() {
		// Print useful name and version information.
		StringBuffer sb = new StringBuffer("Group ");
		sb.append(friendlyName());
		sb.append(": public key: ");
		if (!_groupPublicKey.available()) {
			sb.append("not ready, write to " + 
					AccessControlProfile.groupPublicKeyName(_groupNamespace, friendlyName()));
		} else {
			sb.append(publicKeyName());
		}
		sb.append(" membership list: ");
		if ((null == _groupMembers) || (!_groupMembers.available())) {
			sb.append("not ready, will write to " + 
					AccessControlProfile.groupMembershipListName(_groupNamespace, friendlyName()));
		} else {
			try {
				sb.append(membershipListName());
			} catch (Exception e) {
				Log.warning("Unexpected " + e.getClass().getName() + " exception in getMembershipListName(): " + e.getMessage());
				sb.append("Membership list name unavailable!");
			} 
		}
		return sb.toString();
	}

/**
 * Modify will add and remove members from a Group
 * It can be used to only add members, in which case the membersToRemove list is null
 * or it can be used to only remove members, in which case the membersToAdd list is null
 * If both lists are passed in, then the items in the membersToAdd list are added and the
 * items in the membersToRemove are then removed from the Group members list.
 *  
 * @param membersToAdd - list of group members to be added
 * @param membersToRemove - list of group members to be removed
 * @throws XMLStreamException
 * @throws IOException
 * @throws InvalidKeyException
 * @throws InvalidCipherTextException
 * @throws AccessDeniedException
 * @throws ConfigurationException
 */
	public void modify(java.util.Collection<Link> membersToAdd,
					   java.util.Collection<Link> membersToRemove) 
				throws XMLStreamException, IOException, InvalidKeyException, 
						InvalidCipherTextException, AccessDeniedException, ConfigurationException {
		
		boolean addedMembers = false;
		boolean removedMembers = false;
		
		if (((null == membersToAdd) || (membersToAdd.size() == 0)) && ((null == membersToRemove) || (membersToRemove.size() == 0))) {
			return; // nothing to do
		}
		
		//elaine: you don't want to modify membership list if you dont have permission. 
		//assume no concurrent writer.  
		
		KeyDirectory privateKeyDirectory = privateKeyDirectory(_groupManager.getAccessManager());
		Key privateKeyWrappingKey = privateKeyDirectory.getUnwrappedKey(null);
		if (null == privateKeyWrappingKey) {
			throw new AccessDeniedException("Cannot update group membership, do not have acces rights to private key for group " + friendlyName());
		}

		// Do we need to wait for data to come in? We use this to create new groups as well...
		// so in that case, don't expect any.
		
		// Add before remove so that remove overrides adds.
		if ((null != membersToAdd) && (!membersToAdd.isEmpty())) {
			if (null == _groupMembers.membershipList()) {
				_groupMembers.setData(new Collection(membersToAdd));
				addedMembers = true;
			} else {
				// Optimization: check to see if any were already in there before adding them....
				addedMembers = _groupMembers.membershipList().contents().addAll(membersToAdd);
			}
		}
		if ((null != membersToRemove) && (!membersToRemove.isEmpty()) &&
			_groupMembers.available() && // do we wait if it's not ready? we know one exists.
			(!_groupMembers.isGone()) && 
			(_groupMembers.membershipList().contents().size() > 0)) {
	
			// There were already members. Remove them and make a new key.
			removedMembers = _groupMembers.membershipList().contents().removeAll(membersToRemove);
		}
		
		if (removedMembers) {
			// Don't save membership list till we know we can update private key.
			// If we can't update the private key, this will throw AccessDeniedException.
			newGroupPublicKey(_groupManager, _groupMembers); 
		} else if (addedMembers) {
			// additions only. Don't have to make  a new key if one exists,
			// just rewrap it for added members.
			if (null != _groupPublicKey.publicKey()) {
				updateGroupPublicKey(_groupManager, membersToAdd);
			} else {
				createGroupPublicKey(_groupManager, _groupMembers);
			}
		}
		// Don't actually save the new membership list till we're sure we can update the
		// key.
		_groupMembers.saveToRepository();
	}

	public void delete() throws IOException {
		// Deleting the group -- mark both membership list and public key as GONE.
		_groupMembers.saveAsGone();
		_groupPublicKey.saveAsGone();
	}
}