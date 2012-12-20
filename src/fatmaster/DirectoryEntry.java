package fatmaster;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TreeMap;

/**
 * This class is used to keep data about Directory (and to manage with it)
 *
 * @author George Agapov <george.agapov@gmail.com>
 * @link https://github.com/georgeee/FAT-Master
 * @license http://www.opensource.org/licenses/bsd-license.php
 */
public class DirectoryEntry {

    /**
     * Map, storing number properties of volume
     */
    HashMap<String, Long> props;
    /**
     * Map, storing number properties of volume
     */
    HashMap<String, String> sprops;
    /**
     * Value of byte, storing attributes of entry
     */
    private int attributes;
    /**
     * Short name of directory entr
     */
    public String shortName;
    /**
     * Long name of drectory entry
     */
    public String longName;
    /**
     * True if instance is root directory False otherwise
     */
    public boolean isRootDir = false;
    /**
     * Fat parent instance
     */
    private Fat parent;
    /**
     * Number of first cluster, where the data of directory entry is stored
     */
    public long dataClus;
    /**
     * Map of children Keys - names, as returned by getName() Values -
     * DirectoryEntry instances
     */
    TreeMap<String, DirectoryEntry> children;
    /*
     * Array of keys and array of sizes, used by readKeys()
     */
    private static final String[] deKeys = {"DIR_Name", "DIR_Attr", "DIR_NTRes", "DIR_CrtTimeTenth", "DIR_CrtTime", "DIR_CrtDate", "DIR_LstAccDate", "DIR_FstClusHI", "DIR_WrtTime", "DIR_WrtDate", "DIR_FstClusLO", "DIR_FileSize"};
    private static final int[] deKeys_sz = {11, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 4};
    /*
     * Bitmasks for different attributes of entry
     */
    public static final int ATTR_READ_ONLY = 0x01;
    public static final int ATTR_HIDDEN = 0x02;
    public static final int ATTR_SYSTEM = 0x04;
    public static final int ATTR_VOLUME_ID = 0x08;
    public static final int ATTR_DIRECTORY = 0x10;
    public static final int ATTR_ARCHIVE = 0x20;
    public static final int ATTR_LONG_NAME = ATTR_READ_ONLY | ATTR_HIDDEN | ATTR_SYSTEM | ATTR_VOLUME_ID;
    /**
     * Short name of dot subdirectory
     */
    public static final String DOT_SHORTNAME = ".          ";
    /**
     * Short name of dotdot subdirectory
     */
    public static final String DOTDOT_SHORTNAME = "..         ";

    public DirectoryEntry(Fat parent) {
        this.parent = parent;
        children = null;
        attributes = 0;
        dataClus = -1;
        shortName = longName = null;
        props = new HashMap<>();
        sprops = new HashMap<>();
    }

    /**
     * Whether this is a directory or not
     *
     * @return is(ATTR_DIRECTORY) || isRootDir
     */
    public boolean isDir() {
        return is(ATTR_DIRECTORY) || isRootDir;
    }

    /**
     * Name of the instance, "No name" if both shortName and longName are
     * unknown
     *
     * @return Name of the instance. Preferably long name, short one is return
     * only when long is not specified.
     */
    public String getName() {
        if (longName == null || longName.isEmpty()) {
            if (shortName == null || shortName.isEmpty()) {
                return "No name";
            }
            return shortName;
        }
        return longName;
    }

    /**
     * Finds successor, specified by path
     *
     * @param path Path to successor, we wan't to find
     * @return Found successor, null if path doesn't match any successor in the
     * instance tree
     * @throws IOException
     */
    public DirectoryEntry find(String path) throws IOException {
        String[] parts = path.split("[/\\\\]+");
        if (parts.length == 0) {
            return this;
        }
        DirectoryEntry entry = this;
        for (int i = (parts[0].isEmpty() ? 1 : 0); i < parts.length; i++) {
            entry.retrieveChildren();
            DirectoryEntry _candidate = entry.children.get(parts[i]);
            if (_candidate == null) {
                return null;
            }
            entry = _candidate;
        }
        return entry;
    }

    /**
     * Reads general entry data from byte[] (general entry of directory is not
     * long name entry)
     *
     * @param data byte[] with entry's data in first 32 indexes
     */
    void readGeneralEntry(byte[] data) {
        Fat.readKeysFromBuffer(data, deKeys, deKeys_sz, props, sprops);
        shortName = sprops.get("DIR_Name");
        attributes = props.get("DIR_Attr").intValue();
        if (!isRootDir) {
            dataClus = ((props.get("DIR_FstClusHI") << 16) | props.get("DIR_FstClusLO"));
        }
    }

    /**
     * Reads long name entry data, adds bytes of name part in reversed order to
     * list
     *
     * @param data byte[] with entry's data in first 32 indexes
     * @param list List, to which we should add bytes of name (if we will
     * reverse it later, it will contain bytes of UTF-16BE encoded string)
     */
    void readLNEntry(byte[] data, ArrayList<Byte> list) {
        for (int i = 31; i >= 28; i -= 2) {
            byte s1 = data[i - 1];
            byte s2 = data[i];
            if (s1 == -1 && s2 == -1) {
                continue;
            }
            if (s1 == 0 && s2 == 0) {
                continue;
            }

            list.add(s1);
            list.add(s2);
        }
        for (int i = 25; i >= 14; i -= 2) {
            byte s1 = data[i - 1];
            byte s2 = data[i];
            if (s1 == -1 && s2 == -1) {
                continue;
            }
            if (s1 == 0 && s2 == 0) {
                continue;
            }

            list.add(s1);
            list.add(s2);
        }
        for (int i = 10; i >= 1; i -= 2) {
            byte s1 = data[i - 1];
            byte s2 = data[i];
            if (s1 == -1 && s2 == -1) {
                continue;
            }
            if (s1 == 0 && s2 == 0) {
                continue;
            }

            list.add(s1);
            list.add(s2);
        }
    }

    /**
     * Reads root's child entry from FAT12/FAT16 volumes. Empty entries with
     * 0xE5 in first byte are ignored, if it reaches 0x00 entry or the sectors
     * of root directory are all passed, it puts -1 in lp.a. Otherwise it puts
     * new offset to lp.a.
     *
     * @param offset Offset in bytes from which we should start entry search
     * @param lp LongPair to store offset
     * @return true if new child was read, false otherwise (e.g. in cause of
     * orphan entries)
     * @throws IOException
     */
    public boolean readRootChild(long offset, LongPair lp) throws IOException {
        ArrayList<Byte> list = new ArrayList<>();
        //Going through long-name entries
        boolean started = false;
        while (true) {
            if (offset + 32 > parent.firstDataSector * parent.bytsPerSec) {
                return false;
            }
            parent.file.read(parent.readBuffer, 0, 32);
            offset += 32;
            if (!started) {
                if (parent.readBuffer[0] == ((byte) 0xE5)) {
                    continue;
                } else if (parent.readBuffer[0] == 0) {
                    lp.a = -1;
                    return false;
                } else {
                    if (parent.readBuffer[0] == ((byte) 0x05)) {
                        parent.readBuffer[0] = (byte) 0xE5;
                    }
                    started = true;
                }
            }
            attributes = (int) Fat.getUnsignedIntFromBytes(parent.readBuffer, 11, 1);
            if (!is(ATTR_LONG_NAME)) {
                break;
            }
            readLNEntry(parent.readBuffer, list);
        }
        lp.a = offset;
        readEntry(parent.readBuffer, list);
        return true;
    }

    /**
     * Reads child entry from volume. Empty entries with 0xE5 in first byte are
     * ignored, if it reaches 0x00 entry or the sectors of root directory are
     * all passed, it puts -1 in lp.a. Otherwise it puts new cluster number to
     * lp.a, new offset to lp.b.
     *
     * @param clus Cluster number
     * @param offset Offset in bytes from the cluster beginning
     * @param lp LongPair to store new cluster number and offset
     * @return true if new child was read, false otherwise (e.g. in cause of
     * orphan entries)
     * @throws IOException
     */
    public boolean read(long clus, long offset, LongPair lp) throws IOException {
        ArrayList<Byte> list = new ArrayList<>();
        //Going through long-name entries
        boolean started = false;
        while (true) {
            int read = parent.readBytes(clus, offset, parent.readBuffer, 0, 32, lp);
            clus = lp.a;
            offset = lp.b;
            if (!started) {
                if (parent.readBuffer[0] == ((byte) 0xE5)) {
                    continue;
                } else if (parent.readBuffer[0] == 0) {
                    lp.a = -1;
                    return false;
                } else {
                    if (parent.readBuffer[0] == ((byte) 0x05)) {
                        parent.readBuffer[0] = (byte) 0xE5;
                    }
                    started = true;
                }
            }
            attributes = (int) Fat.getUnsignedIntFromBytes(parent.readBuffer, 11, 1);
            if (read < 32 || !is(ATTR_LONG_NAME)) {
                break;
            }
            readLNEntry(parent.readBuffer, list);
        }
        if (clus < 0) {
            return false;
        }
        readEntry(parent.readBuffer, list);
        return true;
    }

    /**
     * Initializes instance with given general entry's byte[] and reversed list
     * of long name characters.
     *
     * @param data byte[] with entry's data in first 32 indexes
     * @param list List, to which we should add bytes of name (if we will
     * reverse it later, it will contain bytes of UTF-16BE encoded string)
     * @throws UnsupportedEncodingException
     */
    public void readEntry(byte[] generalEntry, ArrayList<Byte> list) throws UnsupportedEncodingException {
        byte[] lnameBytes = new byte[list.size()];
        for (int i = 0; i < lnameBytes.length; i++) {
            lnameBytes[i] = list.get(lnameBytes.length - 1 - i);
        }
        longName = new String(lnameBytes, "utf-16");
        readGeneralEntry(generalEntry);
    }

    /**
     * Reads root entry data.
     *
     * @throws IOException
     */
    public void readRoot() throws IOException {
        if (parent.type == 32) {
            dataClus = parent.props.get("BPB_RootClus");
            parent.moveToClus(dataClus);
        } else {
            parent.moveToSec(parent.fatSz * parent.numFATs + parent.rsvdSecCnt);
        }
    }

    /**
     * Initializes the children map. If instance if file, just executes such
     * code "children = new TreeMap<>();". Otherwise finds all the children of
     * the istance and adds their DirectoryEntry instances to children map.
     *
     * @throws IOException
     */
    public void retrieveChildren() throws IOException {
        if (children == null) {
            children = new TreeMap<>();
            if (!isDir()) {
                return;
            }
            LongPair lp = new LongPair();
            DirectoryEntry newChild = new DirectoryEntry(parent);
            if (isRootDir && parent.type != 32) {
                long offset = parent.fatSz * parent.numFATs + parent.rsvdSecCnt;
                while (offset != -1) {
                    if (newChild.readRootChild(offset, lp)) {
                        if (newChild.is(ATTR_VOLUME_ID) && isRootDir) {
                            retrieveAttributesFromEntry(newChild);
                        } else {
                            children.put(newChild.getName(), newChild);
                        }
                        newChild = new DirectoryEntry(parent);
                    }
                    offset = lp.a;
                }
            } else {
                long clus = dataClus;
                long offset = 0;
                while (clus != -1) {
                    if (newChild.read(clus, offset, lp)) {
                        if (newChild.is(ATTR_VOLUME_ID) && isRootDir) {
                            retrieveAttributesFromEntry(newChild);
                        } else {
                            children.put(newChild.getName(), newChild);
                        }
                        newChild = new DirectoryEntry(parent);
                    }
                    clus = lp.a;
                    offset = lp.b;
                }
            }
        }
    }

    /**
     * Copies information form given DirentoryEntry instance Doesn't copy
     * children, only properties, attributes, short/long names and number of
     * data cluster
     *
     * @param entry Entry with information to copy
     */
    private void retrieveAttributesFromEntry(DirectoryEntry entry) {
        props = (HashMap<String, Long>) entry.props.clone();
        sprops = (HashMap<String, String>) entry.sprops.clone();
        shortName = entry.shortName;
        longName = entry.longName;
        attributes = entry.attributes;
        if (!isRootDir) {
            dataClus = entry.dataClus;
        }
    }

    public long getFileSize() {
        Long fsize = props.get("DIR_FileSize");
        return fsize == null ? 0 : fsize;
    }

    /**
     * Checks if instance fits given attribute mask
     *
     * @param attr Attribute bitmask
     * @return Whether instance fits bitmask or not
     */
    public boolean is(int attr) {
        return (attributes & attr) == attr;
    }

    /**
     * Prints k*3 whitespace characters, used for directory tree formatting.
     *
     * @param k
     */
    public void printIndent(int k) {
        for (int i = 0; i < k; i++) {
            System.out.print("   ");
        }
    }

    /**
     * Prints info about the instance to System.out
     *
     * @throws IOException
     */
    public void printInfo() throws IOException {
        printInfo(0, false);
    }

    /**
     * Prints info about the instance to System.out
     *
     * @param childrenPrintDepth Depth of directory tree
     * @param verbose Should we print full information, or only the names of
     * entry and it's children
     * @throws IOException
     */
    public void printInfo(int childrenPrintDepth, boolean verbose) throws IOException {
        printInfo(childrenPrintDepth, verbose, 0);
    }

    /**
     * Prints info about the instance to System.out
     *
     * @param childrenPrintDepth Depth of directory tree
     * @param verbose Should we print full information, or only the names of
     * entry and it's children
     * @param indent Number of " " groups to prefix every output line
     * @throws IOException
     */
    public void printInfo(int childrenPrintDepth, boolean verbose, int indent) throws IOException {
        retrieveChildren();
        if (verbose) {
            printIndent(indent);
            System.out.println("----------------------");
            printIndent(indent);
            System.out.println("Short name: " + shortName);
            printIndent(indent);
            System.out.println("Long name: " + longName);
            printIndent(indent);
            System.out.println("Read only: " + is(ATTR_READ_ONLY));
            printIndent(indent);
            System.out.println("Hidden: " + is(ATTR_HIDDEN));
            printIndent(indent);
            System.out.println("System: " + is(ATTR_SYSTEM));
            printIndent(indent);
            System.out.println("Directory: " + is(ATTR_DIRECTORY));
            printIndent(indent);
            System.out.println("Archive: " + is(ATTR_ARCHIVE));
            printIndent(indent);
            System.out.println("Size (KB = 2^10 Bytes): " + (getFileSize() / Math.pow(2, 10)));
            printIndent(indent);
            System.out.println("Size (KB = 10^3 Bytes): " + (getFileSize() / Math.pow(10, 3)));
        } else {
            printIndent(indent);
            System.out.println(getName());
        }
        if (verbose && isDir()) {
            printIndent(indent);
            System.out.println("Children count: " + getRealChildrenCount());
        }
        if (isDir() && getRealChildrenCount() > 0 && (childrenPrintDepth > indent)) {
            if (verbose) {
                printIndent(indent);
                System.out.println("Children:");
            }
            Iterator<String> it = children.navigableKeySet().iterator();
            do {
                String key = it.next();
                if (!children.get(key).shortName.equals(DOT_SHORTNAME)
                        && !children.get(key).shortName.equals(DOTDOT_SHORTNAME)) {
                    children.get(key).printInfo(childrenPrintDepth, verbose, indent + 1);
                }
            } while (it.hasNext());
        }
    }

    /**
     * Writes dir/file, to which the instance is refered to dest file.
     *
     * @param dest File or dir on the disk. If null, we simply print the
     * instance's file (or do nothing if it's a directory)
     * @throws IOException
     */
    public void write(File dest) throws IOException {
        if (isDir()) {
            dest.mkdir();
            retrieveChildren();
            Iterator<String> it = children.navigableKeySet().iterator();
            do {
                String key = it.next();
                if (!children.get(key).shortName.equals(DOT_SHORTNAME)
                        && !children.get(key).shortName.equals(DOTDOT_SHORTNAME)) {
                    children.get(key).write(new File(dest.getPath() + File.separator + children.get(key).getName()));
                }
            } while (it.hasNext());
        } else {
            PrintStream out;
            if (dest == null) {
                out = System.out;
            } else {
                out = new PrintStream(dest);
            }
            byte[] buffer = new byte[(int) (parent.bytsPerClus)];
            long clus = dataClus;
            long fSize = props.get("DIR_FileSize");
            while (fSize > 0) {
                parent.moveToClus(clus);
                parent.file.read(buffer, 0, (int) Math.min(fSize, (long) buffer.length));
                out.write(buffer, 0, (int) Math.min(fSize, (long) buffer.length));
                fSize -= buffer.length;
                clus = parent.getNextClus(clus);
            }
            if (dest != null) {
                out.close();
            }
        }
    }

    /**
     * Returns count of children, not counting "." and ".." subdirs.
     *
     * @return Children count
     */
    public int getRealChildrenCount() {
        return (children.size() - (isRootDir ? 0 : 2));
    }
}
