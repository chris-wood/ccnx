package com.parc.ccn.network.daemons.repo;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.ExcludeFilter;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.network.daemons.Daemon;

/**
 * High level repository implementation. Handles communication with
 * ccnd. Low level takes care of actual storage.
 * 
 * @author rasmusse
 *
 */

public class RepositoryDaemon extends Daemon {
	
	private Repository _repo = null;
	private ConcurrentLinkedQueue<ContentObject> _dataQueue = new ConcurrentLinkedQueue<ContentObject>();
	private ConcurrentLinkedQueue<Interest> _interestQueue = new ConcurrentLinkedQueue<Interest>();
	private CCNLibrary _library = null;
	private boolean _started = false;
	private ArrayList<NameAndListener> _repoFilters = new ArrayList<NameAndListener>();
	private ArrayList<DataListener> _currentListeners = new ArrayList<DataListener>();
	private ExcludeFilter markerFilter;
	
	public static final int PERIOD = 1000; // period for interest timeout check in ms.
	
	private class NameAndListener {
		private ContentName name;
		private CCNFilterListener listener;
		private NameAndListener(ContentName name, CCNFilterListener listener) {
			this.name = name;
			this.listener = listener;
		}
	}
	
	private class FilterListener implements CCNFilterListener {

		public int handleInterests(ArrayList<Interest> interests) {
			_interestQueue.addAll(interests);
			return interests.size();
		}
	}
	
	private class DataListener implements CCNInterestListener {
		private long _timer;
		private Interest _interest;
		
		private DataListener(Interest interest) {
			_interest = interest;
			_timer = new Date().getTime();
		}
		
		public Interest handleContent(ArrayList<ContentObject> results,
				Interest interest) {
			_dataQueue.addAll(results);
			_timer = new Date().getTime();
			return Interest.constructInterest(interest.name(), 
						markerFilter, new Integer(Interest.ORDER_PREFERENCE_LEFT 
								| Interest.ORDER_PREFERENCE_ORDER_NAME));
		}
	}
	
	private class InterestTimer extends TimerTask {

		public void run() {
			long currentTime = new Date().getTime();
			synchronized(_currentListeners) {
				for (int i = 0; i < _currentListeners.size(); i++) {
					DataListener listener = _currentListeners.get(i);
					if ((currentTime - listener._timer) > PERIOD) {
						_library.cancelInterest(listener._interest, listener);
						_currentListeners.remove(i--);
					}
				}	
			}	
		}	
	}
	
	protected class RepositoryWorkerThread extends Daemon.WorkerThread {

		private static final long serialVersionUID = -6093561895394961537L;
		
		protected RepositoryWorkerThread(String daemonName) {
			super(daemonName);
		}
		
		public void work() {
			while (_started) {
				
				ContentObject data = null;
				do {
					data = _dataQueue.poll();
					if (data != null) {
						try {
							if (_repo.checkPolicyUpdate(data)) {
								resetNameSpace();
							} else {
								Library.logger().finer("Saving content in: " + data.name().toString());
								_repo.saveContent(data);
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				} while (data != null) ;
				
				Interest interest = null;
				do {
					interest = _interestQueue.poll();
					if (interest != null)
					try {
						byte[] marker = interest.name().component(interest.name().count() - 1);
						if (Arrays.equals(marker, CCNBase.REPO_START_WRITE)) {
							startReadProcess(interest);
						} else {
							ContentObject content = _repo.getContent(interest);
							if (content != null) {
								_library.put(content);
							}
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				} while (interest != null);
				
				Thread.yield();  // Should we sleep?
			}
		}
		
		public void initialize() {
			try {
				resetNameSpace();
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			byte[][]markerOmissions = new byte[1][];
			markerOmissions[0] = CCNBase.REPO_START_WRITE;
			markerFilter = Interest.constructFilter(markerOmissions);
			
			Timer periodicTimer = new Timer(true);
			periodicTimer.scheduleAtFixedRate(new InterestTimer(), PERIOD, PERIOD);
		}
		
		public void finish() {
			_started = false;
		}
	}
	
	public RepositoryDaemon() {
		super();
		_daemonName = "repository";
		Library.logger().info("Starting " + _daemonName + "...");				
		_started = true;
		
		try {
			_library = CCNLibrary.open();
			_repo = new RFSImpl();
		} catch (Exception e1) {
			e1.printStackTrace();
			System.exit(0);
		} 
	}
	
	public void initialize(String[] args, Daemon daemon) {
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("-log")) {
				if (args.length < i + 2) {
					usage();
					return;
				}
				try {
					Level level = Level.parse(args[i + 1]);
					Library.logger().setLevel(level);
				} catch (IllegalArgumentException iae) {
					usage();
					return;
				}
			}
		}
		try {
			_repo.initialize(args);
		} catch (InvalidParameterException ipe) {
			usage();
		} catch (RepositoryException e) {
			e.printStackTrace();
		}
	}
	
	protected void usage() {
		try {
			System.out.println("usage: " + this.getClass().getName() + 
						_repo.getUsage() + "[-start | -stop | -interactive] [-log <level>]");
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.exit(0);
	}

	protected WorkerThread createWorkerThread() {
		return new RepositoryWorkerThread(daemonName());
	}
	
	private void resetNameSpace() throws IOException {
		ArrayList<NameAndListener> newIL = new ArrayList<NameAndListener>();
		ArrayList<ContentName> newNameSpace = _repo.getNamespace();
		if (newNameSpace == null)
			newNameSpace = new ArrayList<ContentName>();
		ArrayList<NameAndListener> unMatchedOld = new ArrayList<NameAndListener>();
		ArrayList<ContentName> unMatchedNew = new ArrayList<ContentName>();
		getUnMatched(_repoFilters, newNameSpace, unMatchedOld, unMatchedNew);
		for (NameAndListener oldName : unMatchedOld) {
			_library.unregisterFilter(oldName.name, oldName.listener);
		}
		for (ContentName newName : unMatchedNew) {
			FilterListener listener = new FilterListener();
			_library.registerFilter(newName, listener);
			newIL.add(new NameAndListener(newName, listener));
		}
		_repoFilters = newIL;
	}
	
	private void getUnMatched(ArrayList<NameAndListener> oldIn, ArrayList<ContentName> newIn, 
			ArrayList<NameAndListener> oldOut, ArrayList<ContentName>newOut) {
		newOut.addAll(newIn);
		for (NameAndListener ial : oldIn) {
			boolean matched = false;
			for (ContentName name : newIn) {
				if (ial.name.equals(name)) {
					newOut.remove(name);
					matched = true;
					break;
				}
			}
			if (!matched)
				oldOut.add(ial);
		}
	}
	
	private void startReadProcess(Interest interest) {
		ContentName listeningName = new ContentName(interest.name().count() - 1, 
				interest.name().components(), interest.name().prefixCount());
		try {
			DataListener listener = new DataListener(interest);
			synchronized(_currentListeners) {
				_currentListeners.add(listener);
			}
			Interest readInterest = Interest.constructInterest(listeningName, markerFilter, null);
			_library.expressInterest(readInterest, listener);
			_library.put(interest.name(), _repo.getRepoInfo());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SignatureException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public static void main(String[] args) {
		Daemon daemon = null;
		try {
			daemon = new RepositoryDaemon();
			runDaemon(daemon, args);
			
		} catch (Exception e) {
			System.err.println("Error attempting to start daemon.");
			Library.logger().warning("Error attempting to start daemon.");
			Library.warningStackTrace(e);
		}
	}
}
