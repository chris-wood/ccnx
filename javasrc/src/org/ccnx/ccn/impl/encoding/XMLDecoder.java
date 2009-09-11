package org.ccnx.ccn.impl.encoding;

import java.io.InputStream;
import java.util.TreeMap;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.protocol.CCNTime;

public interface XMLDecoder {
	
	/**
	 * Handles writing any necessary start document, etc.
	 * @param istream
	 * @throws XMLStreamException
	 */
	public void beginDecoding(InputStream istream) throws XMLStreamException;
	
	public void endDecoding() throws XMLStreamException;

	public void readStartDocument() throws XMLStreamException;

	public void readEndDocument() throws XMLStreamException;
	
	public void readStartElement(String startTag) throws XMLStreamException;
		
	public void readStartElement(String startTag,
			TreeMap<String,String> attributes) throws XMLStreamException;

	public boolean peekStartElement(String startTag) throws XMLStreamException;
	
	public String peekStartElement() throws XMLStreamException;
	
	public void readEndElement() throws XMLStreamException;

	public String readUTF8Element(String startTag,
			TreeMap<String,String> attributes) throws XMLStreamException;
	
	public String readUTF8Element(String startTag) throws XMLStreamException;
	
	public byte [] readBinaryElement(String startTag, TreeMap<String,String> attributes) throws XMLStreamException;
	
	public byte [] readBinaryElement(String startTag) throws XMLStreamException;
	
	/**
	 * Encapsulate integer parsing.
	 */
	public Integer readIntegerElement(String startTag) throws XMLStreamException;

	/**
	 * Encapsulate our handling of timestamps.
	 */
	public CCNTime readDateTime(String startTag) throws XMLStreamException;
	
	public void pushXMLDictionary(BinaryXMLDictionary dictionary);
	
	public BinaryXMLDictionary popXMLDictionary();
}