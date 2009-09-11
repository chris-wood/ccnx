package org.ccnx.ccn.test.protocol;

import static org.junit.Assert.*;

import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.protocol.BloomFilter;
import org.ccnx.ccn.protocol.ExcludeAny;
import org.ccnx.ccn.protocol.ExcludeComponent;
import org.ccnx.ccn.protocol.Exclude;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;


public class ExcludeTest {

	static ArrayList<Exclude.Element> al;
	static final byte [] b0 = "0".getBytes();
	static final byte [] b1 = "1".getBytes();
	static final byte [] b3 = "3".getBytes();
	static final byte [] b06 = "06".getBytes();
	static final byte [] b000 = "000".getBytes();
	static ExcludeComponent c1 = new ExcludeComponent(b1);
	static ExcludeComponent c3 = new ExcludeComponent(b3);
	static ExcludeComponent c06 = new ExcludeComponent(b06);
	static ExcludeAny any = new ExcludeAny();
	static BloomFilter bloom;
	static Exclude ef;
	static byte [][] array = { b1, b06 };
	
	private static byte [] bloomSeed = "test".getBytes();
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		al = new ArrayList<Exclude.Element>();
		bloom = new BloomFilter(13, bloomSeed);
		bloom.insert(b3);
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}
	
	@Before
	public void setUpBefore() {
		al.clear();
	}

	@Test (expected=IllegalArgumentException.class)
	public void testExcludeArrayListOfElementFails() {
		al.add(c06);
		al.add(c1);
		new Exclude(al);
	}

	@Test (expected=IllegalArgumentException.class)
	public void testExcludeArrayListOfElementFails2() {
		al.add(c1);
		al.add(c06);
		al.add(bloom);
		al.add(any);
		new Exclude(al);
	}

	@Test (expected=IllegalArgumentException.class)
	public void testExcludeArrayListOfElementFails3() {
		al.add(c1);
		al.add(any);
		al.add(bloom);
		al.add(c06);
		new Exclude(al);
	}

	@Test
	public void testExcludeArrayListOfElement() {
		al.add(c1);
		al.add(any);
		al.add(c3);
		al.add(c06);
		al.add(bloom);
		new Exclude(al);
	}

	@Test
	public void testExcludeByteArrayArray() {
		ef = new Exclude(array);
		assertTrue(ef.match(b1));
		assertFalse(ef.match(b3));
		assertTrue(ef.match(b06));
	}

	@Test
	public void testUptoFactory() {
		ef = Exclude.uptoFactory(b3);
		assertTrue(ef.match(b1));
		assertTrue(ef.match(b3));
		assertFalse(ef.match(b06));
	}

	@Test
	public void testFactory() {
		ef = Exclude.factory(null);
		assertNull(ef);
		ef = Exclude.factory(array);
		assertTrue(ef.match(b1));
		assertFalse(ef.match(b3));
		assertTrue(ef.match(b06));
	}

	@Test
	public void testMatchBloom() {
		al.add(c1);
		al.add(bloom);
		al.add(c06);
		ef = new Exclude(al);
		assertFalse(ef.match(b0));
		assertTrue(ef.match(b3));
		assertTrue(ef.match(b06));
		assertFalse(ef.match(b000));
	}

	@Test
	public void testAdd() {
		ef = Exclude.uptoFactory(b0);
		assertFalse(ef.match(b1));
		ef.add(array);
		assertTrue(ef.match(b0));
		assertTrue(ef.match(b1));
		assertTrue(ef.match(b06));
	}

	@Test
	public void testEmpty() {
		ef = Exclude.uptoFactory(b0);
		assertFalse(ef.empty());
		ArrayList<Exclude.Element> empty = new ArrayList<Exclude.Element>();
		ef = new Exclude(empty);
		assertTrue(ef.empty());
	}

	//@Test
	//public void testCompareTo() {
	//	fail("Not yet implemented"); // TODO
	//}

	@Test
	public void testEqualsObject() {
		ef = Exclude.uptoFactory(b1);
		ArrayList<Exclude.Element> a = new ArrayList<Exclude.Element>();
		a.add(any);
		a.add(c1);
		assertEquals(ef, new Exclude(a));
	}

	@Test
	public void testSize() {
		ef = Exclude.uptoFactory(b1);
		assertTrue(ef.size() == 2);
	}

	@Test
	public void testEncodeDecode() throws XMLStreamException {
		al.add(c1);
		al.add(any);
		al.add(c3);
		al.add(c06);
		al.add(bloom);
		ef = new Exclude(al);
		Exclude ef2 = new Exclude();
		ef2.decode(ef.encode());
		assertTrue(ef.equals(ef2));
	}
}