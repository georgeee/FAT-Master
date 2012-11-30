package fatmaster;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashMap;

/**
 * This class deals with Fat volume.
 * @author georgeee
 */
public class Fat {

    private static final String[] keys = {"BS_jmpBoot", "BS_OEMName", "BPB_BytsPerSec", "BPB_SecPerClus", "BPB_RsvdSecCnt", "BPB_NumFATs", "BPB_RootEntCnt", "BPB_TotSec16", "BPB_Media", "BPB_FATSz16", "BPB_SecPerTrk", "BPB_NumHeads", "BPB_HiddSec", "BPB_TotSec32"};
    private static final int[] keys_sz = {3, 8, 2, 1, 2, 1, 2, 2, 1, 2, 2, 2, 4, 4};
    private static final String[] keys12_16 = {"BS_DrvNum", "BS_Reserved1", "BS_BootSig", "BS_VolID", "BS_VolLab", "BS_FilSysType"};
    private static final int[] keys12_16_sz = {1, 1, 1, 4, 11, 8};
    private static final String[] keys32 = {"BPB_FATSz32", "BPB_ExtFlags", "BPB_FSVer", "BPB_RootClus", "BPB_FSInfo", "BPB_BkBootSec", "BPB_Reserved", "BS_DrvNum", "BS_Reserved1", "BS_BootSig", "BS_VolID", "BS_VolLab", "BS_FilSysType"};
    private static final int[] keys32_sz = {4, 2, 2, 4, 2, 2, 12, 1, 1, 1, 4, 11, 8};
    private static final String[] keysFSInfo = {"FSI_LeadSig", "FSI_Reserved1", "FSI_StrucSig", "FSI_Free_Count", "FSI_Nxt_Free", "FSI_Reserved2", "FSI_TrailSig"};
    private static final int[] keysFSInfo_sz = {4, 480, 4, 4, 4, 12, 4};
    /**
     * 12 - fat12, 16 - fat16, 32 - fat32
     */
    short type;
    long EOC;
    RandomAccessFile file = null;
    HashMap<String, Long> props;
    HashMap<String, String> sprops;
    DirectoryEntry root;
    byte[] readBuffer;
    int rsvdSecCnt, bytsPerSec, secPerClus, bytsPerClus, rootDirSectors, numFATs;
    long fatSz, totSec, dataSec, countOfClusters, firstDataSector, freeSpace, totSpace;

    public Fat() {
        readBuffer = new byte[1500];
    }

    /**
     * Initializes Fat class instance with FAT volume from _file
     *
     * @param _file File with FAT volume image (or smth like /dev/sdc1)
     * @throws IOException
     */
    public void open(File _file) throws IOException {
        if (file != null) {
            file.close();
        }
        file = new RandomAccessFile(_file, "r");
        props = new HashMap<>();
        sprops = new HashMap<>();

        readKeys(keys, keys_sz);

        numFATs = props.get("BPB_NumFATs").intValue();
        bytsPerSec = props.get("BPB_BytsPerSec").intValue();
        secPerClus = props.get("BPB_SecPerClus").intValue();
        bytsPerClus = bytsPerSec * secPerClus;
        rsvdSecCnt = props.get("BPB_RsvdSecCnt").intValue();

        rootDirSectors = ((props.get("BPB_RootEntCnt").intValue() * 32) + (bytsPerSec - 1)) / bytsPerSec;
        if (props.get("BPB_FATSz16") != 0) {
            fatSz = props.get("BPB_FATSz16");
        } else {
            readKeys(keys32, keys32_sz);
            fatSz = props.get("BPB_FATSz32");
        }
        if (props.get("BPB_TotSec16") != 0) {
            totSec = props.get("BPB_TotSec16");
        } else {
            totSec = props.get("BPB_TotSec32");
        }
        dataSec = totSec - (rsvdSecCnt + (fatSz * numFATs) + rootDirSectors);
        countOfClusters = dataSec / secPerClus;

        //Exploring the type of our FAT
        if (countOfClusters < 4085) {
            //Volume is FAT12
            type = 12;
            EOC = 0x0FF8;

        } else if (countOfClusters < 65525) {
            //Volume is FAT16
            type = 16;
            EOC = 0xFFF8;
        } else {
            //Volume is FAT32
            type = 32;
            EOC = 0x0FFFFFF8L;
        }
        if (type == 32) {
            if (props.get("BPB_FATSz32") == null) {
                readKeys(keys32, keys32_sz);
            }
        } else {
            readKeys(keys12_16, keys12_16_sz);
        }

        firstDataSector = rsvdSecCnt + (numFATs * fatSz) + rootDirSectors;
        freeSpace = -1;
        if (type == 32) {
            moveToSec(props.get("BPB_FSInfo"));
            readKeys(keysFSInfo, keysFSInfo_sz);
            if (props.get("FSI_Free_Count") != 0xFFFFFFFFL) {
                freeSpace = props.get("FSI_Free_Count") * bytsPerClus;
            }
        }

        if (freeSpace == -1) {
            moveToSec(rsvdSecCnt);
            freeSpace = 0;
            for (int i = 0; i < countOfClusters; i++) {
                if (getNextClus(i) == 0) {
                    freeSpace++;
                }
            }
            freeSpace *= bytsPerClus;
        }
        totSpace = countOfClusters * bytsPerClus;
        root = new DirectoryEntry(this);
        root.isRootDir = true;
        root.readRoot();
    }

    /**
     * Closes FAT volume file
     *
     * @throws IOException
     */
    public void close() throws IOException {
        file.close();
    }

    /**
     * Reads bytes from cluster (following the cluster chain, if the first
     * cluster ends)
     *
     * @param clus Cluster number
     * @param offset Offset in bytes from beginning of the cluster
     * @param bytes Array of bytes, to which we should write what we've read
     * @param bytes_offset Offset of the bytes array
     * @param count Count of the bytes, we should read
     * @param nValues LongPair instance, to which we will put new cluster number
     * and offset (they could change during reading)
     * @return Count of bytes we successfully read (may differ from requested
     * count if it's impossible to read count bytes)
     * @throws IOException
     */
    int readBytes(long clus, long offset, byte[] bytes, int bytes_offset, int count, LongPair nValues) throws IOException {
        if (clus < 0) {
            if (nValues != null) {
                nValues.set(clus, offset);
            }
            return 0;
        }
        for (; offset >= bytsPerClus; offset -= bytsPerClus) {
            clus = getNextClus(clus);
            if (clus < 0) {
                if (nValues != null) {
                    nValues.set(-1, 0);
                }
                return 0;
            }
        }
        int read = 0;
        if (count > bytsPerClus - offset) {
            moveToClus(clus, offset);
            file.read(bytes, bytes_offset, bytsPerClus - (int) offset);
            read += bytsPerClus - offset;
            clus = getNextClus(clus);
            offset = 0;
        }
        if (clus < 0) {
            if (nValues != null) {
                nValues.set(-1, 0);
            }
            return read;
        }
        while (count >= bytsPerClus) {
            moveToClus(clus);
            file.read(bytes, bytes_offset + read, bytsPerClus);
            read += bytsPerClus;
            count -= bytsPerClus;
            clus = getNextClus(clus);
            if (clus < 0) {
                if (nValues != null) {
                    nValues.set(-1, 0);
                }
                return read;
            }
        }
        moveToClus(clus, offset);
        file.read(bytes, bytes_offset + read, count);
        if (nValues != null) {
            nValues.set(clus, offset + count);
        }
        return read + count;
    }

    /**
     * Moves file pointer to specified cluster
     *
     * @param clus Cluster number
     * @throws IOException
     */
    void moveToClus(long clus) throws IOException {
        moveToClus(clus, 0);
    }

    /**
     * Moves file pointer to specified cluster and offset
     *
     * @param clus Cluster number
     * @param offset Offset in bytes from beginning of the cluster
     * @throws IOException
     */
    void moveToClus(long clus, long offset) throws IOException {
        file.seek(getFirstSecOfClus(clus) * bytsPerSec + offset);
    }

    /**
     * Reads values, starting from current file pointer. It puts number values
     * (less than 5 bytes) in props map and String values (more than 4 bytes) to
     * sprops map. keys[i] is treated as a key for the value, given by bytes
     * from summ(keys[0..i-1]) to summ(keys[0..i-1]) + keys_sz[i]
     *
     * @param keys Array of keys
     * @param keys_sz Array of sizes
     * @param props Map of number values
     * @param sprops Map of string values
     * @throws IOException
     */
    void readKeys(String[] keys, int[] keys_sz, HashMap<String, Long> props, HashMap<String, String> sprops) throws IOException {
        int summ = 0;
        for (int i = 0; i < keys.length; i++) {
            summ += keys_sz[i];
        }
        file.read(readBuffer, 0, summ);
        readKeysFromBuffer(readBuffer, keys, keys_sz, props, sprops);
    }

    /**
     * Reads values from byte array. It puts number values (less than 5 bytes)
     * in props map and String values (more than 4 bytes) to sprops map. keys[i]
     * is treated as a key for the value, given by bytes from summ(keys[0..i-1])
     * to summ(keys[0..i-1]) + keys_sz[i]
     *
     * @param buffer Array of bytes, from which we should read (it reads
     * starting from the zero position)
     * @param keys Array of keys
     * @param keys_sz Array of sizes
     * @param props Map of number values
     * @param sprops Map of string values
     */
    static void readKeysFromBuffer(byte[] buffer, String[] keys, int[] keys_sz, HashMap<String, Long> props, HashMap<String, String> sprops) {
        int offset = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys_sz[i] < 5) {
                props.put(keys[i], getUnsignedIntFromBytes(buffer, offset, keys_sz[i]));
            } else {
                sprops.put(keys[i], new String(buffer, offset, keys_sz[i]));
            }
            offset += keys_sz[i];
        }
    }

    /**
     * Converts bytes from bytes byte array to unsigned number.
     *
     * @param bytes Array of bytes
     * @return Unsigned number
     */
    static long getUnsignedIntFromBytes(byte[] bytes) {
        return getUnsignedIntFromBytes(bytes, 0);
    }

    /**
     * Converts bytes from bytes byte array to unsigned number, starting from
     * the offset position.
     *
     * @param bytes Array of bytes
     * @param offset Offset of array
     * @return Unsigned number
     */
    static long getUnsignedIntFromBytes(byte[] bytes, int offset) {
        return getUnsignedIntFromBytes(bytes, offset, bytes.length - offset);
    }

    /**
     * Converts bytes from bytes byte array to unsigned number, starting from
     * the offset position. It reads length bytes.
     *
     * @param bytes Array of bytes
     * @param offset Offset of array
     * @param length Count of bytes to read
     * @return Unsigned number
     */
    static long getUnsignedIntFromBytes(byte[] bytes, int offset, int length) {
        long val = 0;
        for (int j = offset; j < length + offset && j < bytes.length; j++) {
            val |= (0xFF & ((long) bytes[j])) << (8 * (j - offset));
        }
        val &= 0xFFFFFFFFL;
        return val;
    }

    /**
     * Reads size byte unsigned number, starting from the file pointer
     *
     * @param size Count of bytes, the number consists of.
     * @return Unsigned number
     * @throws IOException
     */
    long readNumber(int size) throws IOException {
        file.read(readBuffer, 0, size);
        return getUnsignedIntFromBytes(readBuffer, 0, size);
    }

    /**
     * Reads values, starting from current file pointer. It puts number values
     * (less than 5 bytes) in this.props map and String values (more than 4
     * bytes) to this.sprops map. keys[i] is treated as a key for the value,
     * given by bytes from summ(keys[0..i-1]) to summ(keys[0..i-1]) + keys_sz[i]
     *
     * @param keys Array of keys
     * @param keys_sz Array of sizes
     * @throws IOException
     */
    void readKeys(String[] keys, int[] keys_sz) throws IOException {
        readKeys(keys, keys_sz, props, sprops);
    }

    /**
     * Moves file pointer to the specified sector and offset.
     *
     * @param sector Sector number
     * @param offset Offset in bytes
     * @throws IOException
     */
    void moveToSec(long sector, long offset) throws IOException {
        file.seek(bytsPerSec * sector + offset);
    }

    /**
     * Moves file pointer to the specified sector.
     *
     * @param sector Sector number
     * @throws IOException
     */
    void moveToSec(long sector) throws IOException {
        moveToSec(sector, 0);
    }

    /**
     * Gets next cluster number from the cluster chain.
     *
     * @param clus Cluster number
     * @return Next cluster number if exists one in the cluster chain. -1
     * otherwise
     * @throws IOException
     */
    long getNextClus(long clus) throws IOException {
        long fatOffset;
        if (type == 12) {
            fatOffset = clus + clus / 2;
        } else if (type == 16) {
            fatOffset = clus * 2;
        } else {
            fatOffset = clus * 4;
        }
        long fatSecNum = rsvdSecCnt + (fatOffset / bytsPerSec);
        long fatEntOffset = fatOffset % bytsPerSec;
        moveToSec(fatSecNum, fatEntOffset);
        long val;
        if (type == 12) {//12 bits
            val = readNumber(2);
            if ((clus & 1) == 1) {
                val >>= 4;
            } else {
                val &= 0x0FFF;
            }
        } else if (type == 16) {//16 bits
            val = readNumber(2);
        } else {//28 bits
            val = readNumber(4) & 0x0FFFFFFFL;
        }
        if (val >= EOC) {
            return -1;
        }
        return val;
    }

    /**
     * Writes file/dir to the disk (or, if _dest == null prints file to
     * System.out)
     *
     * @param path Path to file/dir, null is assumed as '/' (root)
     * @param _dest Destination file/dir. If null, prints file from path to
     * System.out
     * @throws IOException
     */
    public void write(String path, String _dest) throws IOException {
        if (path == null) {
            path = "/";
        }
        DirectoryEntry de = root.find(path);
        if (de == null) {
            System.err.println("No such path");
            return;
        }
        if (!de.isDir() && _dest == null) {
            de.write(null);
            System.out.println();
            return;
        }
        File _file = new File(_dest);
        if (!_file.exists() && (_file.getParentFile() != null && !_file.getParentFile().exists())) {
            System.err.println("Neither file nor it's parent directory exist");
            return;
        }
        File dest;
        if (!_file.exists()) {
            dest = _file;
        } else if (_file.isFile() && de.isDir()) {
            System.err.println("Can't write directory to file");
            return;
        } else {
            dest = new File(_file.getPath() + File.separator + de.getName());
        }
        de.write(dest);
    }

    /**
     * Returns sector number of the first sector of specified cluster.
     *
     * @param clus Cluster number
     * @return Sector number
     */
    long getFirstSecOfClus(long clus) {
        return ((clus - 2) * secPerClus) + firstDataSector;
    }

    /**
     * Return the cluster, to which specified sector belongs to.
     *
     * @param sector Sector number
     * @return Cluster number
     */
    long getClusOfSec(long sector) {
        sector -= firstDataSector;
        sector /= secPerClus;
        return sector + 2;
    }

    /**
     * Prints information about the path to System.out. If path == null prints
     * info about the whole volume. If verbose == true, prints only names of
     * files/dirs
     *
     * @param path Path, information about which we should print
     * @param depth Depth of file/directory tree to print (-1 means print it
     * full, 0 - only the file/dir from path)
     * @param verbose Whether to print information, or just names of files/dirs
     * @throws IOException
     */
    public void printInfo(String path, int depth, boolean verbose) throws IOException {
        if (depth < 0) {
            depth = Integer.MAX_VALUE;
        }
        if (path == null) {
            if (verbose) {
                System.out.printf("Type of FAT: FAT%d\n", type);
                System.out.println("======= Space information =======");
                System.out.println("Free space (MB = 10^6 Bytes): " + ((long) (freeSpace / Math.pow(10, 6))));
                System.out.println("Free space (MB = 2^20 Bytes): " + ((long) (freeSpace >> 20)));
                System.out.println("Total space (MB = 10^6 Bytes): " + ((long) (totSpace / Math.pow(10, 6))));
                System.out.println("Total space (MB = 2^20 Bytes): " + ((long) (totSpace >> 20)));
                //Constraints
                System.out.println("======= Constraints =======");
                Object[] map_keys = props.keySet().toArray();
                Arrays.sort(map_keys);
                for (int i = 0; i < map_keys.length; i++) {
                    System.out.printf("%s: %d\n", (String) map_keys[i], props.get((String) map_keys[i]));
                }
                map_keys = sprops.keySet().toArray();
                Arrays.sort(map_keys);
                for (int i = 0; i < map_keys.length; i++) {
                    System.out.printf("%s: %s\n", (String) map_keys[i], sprops.get((String) map_keys[i]));
                }
                System.out.println("======= Root Directory =======");
            }
            path = "/";
        }
        DirectoryEntry de = root.find(path);
        if (de == null) {
            System.err.println("No such path");
        } else {
            de.printInfo(depth, verbose);
        }
    }
}