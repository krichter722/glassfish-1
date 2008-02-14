/*
 * The contents of this file are subject to the terms 
 * of the Common Development and Distribution License 
 * (the License).  You may not use this file except in
 * compliance with the License.
 * 
 * You can obtain a copy of the license at 
 * https://glassfish.dev.java.net/public/CDDLv1.0.html or
 * glassfish/bootstrap/legal/CDDLv1.0.txt.
 * See the License for the specific language governing 
 * permissions and limitations under the License.
 * 
 * When distributing Covered Code, include this CDDL 
 * Header Notice in each file and include the License file 
 * at glassfish/bootstrap/legal/CDDLv1.0.txt.  
 * If applicable, add the following below the CDDL Header, 
 * with the fields enclosed by brackets [] replaced by
 * you own identifying information: 
 * "Portions Copyrighted [year] [name of copyright owner]"
 * 
 * Copyright 2006 Sun Microsystems, Inc. All rights reserved.
 */

/*
 * MemoryMappedArchive.java
 *
 * Created on September 6, 2002, 2:58 PM
 */

package com.sun.enterprise.deployment.deploy.shared;

import com.sun.enterprise.util.shared.ArchivistUtils;
import org.glassfish.api.deployment.archive.ReadableArchive;
import org.jvnet.hk2.annotations.Service;

import java.io.*;
import java.net.URI;
import java.util.Enumeration;
import java.util.Vector;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 *
 * @author  Jerome Dochez
 */
@Service
public class MemoryMappedArchive extends JarArchive implements ReadableArchive {
    
    byte[] file;
    
    /** Creates a new instance of MemoryMappedArchive */
    protected MemoryMappedArchive() {
	// for use by subclasses
    }

    /** Creates a new instance of MemoryMappedArchive */
    public MemoryMappedArchive(InputStream is) throws IOException {
        read(is);
    }

    public byte[] getByteArray() {
        return file;
    }
    
    private void read(InputStream is) throws IOException{
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ArchivistUtils.copy(new BufferedInputStream(is), new BufferedOutputStream(baos));
        file = baos.toByteArray();
        
    }
    
    public void open(URI uri) throws IOException {
        File in = new File(uri);
        if (!in.exists()) {
            throw new FileNotFoundException(uri.getSchemeSpecificPart());
        }
        FileInputStream is = new FileInputStream(in);
        read(is);
    }
    
    // copy constructor
    public MemoryMappedArchive(ReadableArchive source) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(baos));
        for (Enumeration elements = source.entries();elements.hasMoreElements();) {
            String elementName = (String) elements.nextElement();
            InputStream is = source.getEntry(elementName);
            jos.putNextEntry(new ZipEntry(elementName));
            ArchivistUtils.copyWithoutClose(is, jos);            
            is.close();
            jos.flush();
            jos.closeEntry();
        }
        jos.close();
        file = baos.toByteArray();            
    }
    
    /**
     * close the abstract archive
     */
    public void close() throws IOException {
    }
        
    /**
     * delete the archive
     */
    public boolean delete() {
        return false;
    }
    
    /**
     * @return an @see java.util.Enumeration of entries in this abstract
     * archive
     */
    public Enumeration entries() {
        Vector entries = new Vector();
        try {
            JarInputStream jis = new JarInputStream(new ByteArrayInputStream(file));
            ZipEntry ze;
            while ((ze=jis.getNextEntry())!=null) {
                entries.add(ze.getName());
            }
            jis.close();
        } catch(IOException ioe) {
            ioe.printStackTrace();
        }
        return entries.elements();        
    }
    
	/**
	 *  @return an @see java.util.Enumeration of entries in this abstract
	 * archive, providing the list of embedded archive to not count their 
	 * entries as part of this archive
	 */
	 public Enumeration entries(Enumeration embeddedArchives) {
	// jar file are not recursive    
	return entries();
	}
    
    /**
     * @return true if this archive exists
     */
    public boolean exists() {
        return false;
    }
    
    /**
     * @return the archive uri
     */
    public String getPath() {
        return null;
    }
    
    /**
     * Get the size of the archive
     * @return tje the size of this archive or -1 on error
     */
    public long getArchiveSize() throws NullPointerException, SecurityException {
        return(file.length);
    }
    
    public URI getURI() {
        return null;
    }
    
    /**
     * create or obtain an embedded archive within this abstraction.
     *
     * @param name the name of the embedded archive.
     */
    public ReadableArchive getSubArchive(String name) throws IOException {
        InputStream is = getEntry(name);
        if (is!=null) {
            ReadableArchive archive = new MemoryMappedArchive(is);
            is.close();
            return archive;
        }
        return null;
    }
    
    /**
     * @return a @see java.io.InputStream for an existing entry in
     * the current abstract archive
     * @param name the entry name
     */
    public InputStream getEntry(String name) throws IOException {
        JarInputStream jis = new JarInputStream(new ByteArrayInputStream(file));
        ZipEntry ze;
        while ((ze=jis.getNextEntry())!=null) {
            if (ze.getName().equals(name)) 
                return new BufferedInputStream(jis);
        }
        return null;        
    }

    /**
     * Returns the entry size for a given entry name or 0 if not known
     *
     * @param name the entry name
     * @return the entry size
     */
    public long getEntrySize(String name) {
        try {
            JarInputStream jis = new JarInputStream(new ByteArrayInputStream(file));
            ZipEntry ze;
            while ((ze=jis.getNextEntry())!=null) {
                if (ze.getName().equals(name)) {
                    return ze.getSize();
                }
            }
        } catch(IOException e) {
            return 0;
        }
        return 0;
    }
    
    /**
     * @return the manifest information for this abstract archive
     */
    public Manifest getManifest() throws IOException {
        JarInputStream jis = new JarInputStream(new ByteArrayInputStream(file));
        Manifest m = jis.getManifest();
        jis.close();
        return m;
    }
    
    /**
     * rename the archive
     *
     * @param name the archive name
     */
    public boolean renameTo(String name) {
        return false;
    }        
    
    /**
     * Returns the name for the archive.
     * <p>
     * For a MemoryMappedArhive there is no name, so an empty string is returned.
     * @return the name of the archive
     * 
     */
    @Override
    public String getName() {
        return "";
    }
}
