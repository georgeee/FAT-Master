/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package fatmaster;

import java.io.*;
import java.util.*;

/**
 *
 * @author georgeee
 */
public class FatMaster {

    static final int INFO = (1 << 1);
    static final int LIST = (1 << 2);
    static final int PRINT = (1 << 3);
    static final int SAVE = (1);
    int runningMode = 0;

    private boolean isNeeded(int mode_mask) {
        return (runningMode & mode_mask) == mode_mask;
    }
    String fileName = null, info_path = null, list_path = null, save_from = null, save_to = null, print_path = null;
    int list_depth = -1;
    int info_depth = -1;
    final String[] reservedArgs = {"-f", "-i", "-p", "-l", "-ld", "-id", "-s", "-c"};

    private boolean isReservedArg(String s) {
        for (int i = 0; i < reservedArgs.length; i++) {
            if (s.equals(reservedArgs[i])) {
                return true;
            }
        }
        return false;
    }

    private FatMaster(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-f":
                    fileName = args[++i];
                    break;
                case "-ld":
                    list_depth = Integer.parseInt(args[++i].trim());
                    break;
                case "-id":
                    info_depth = Integer.parseInt(args[++i].trim());
                    break;
                case "-i":
                    runningMode |= INFO;
                    if (i + 1 < args.length && !isReservedArg(args[i + 1])) {
                        info_path = args[++i];
                    }
                    break;
                case "-p":
                    runningMode |= PRINT;
                    print_path = args[++i];
                    break;
                case "-l":
                    runningMode |= LIST;
                    if (i + 1 < args.length && !isReservedArg(args[i + 1])) {
                        list_path = args[++i];
                    }
                    break;
                case "-s":
                    runningMode |= SAVE;
                    if (i + 1 < args.length && !isReservedArg(args[i + 1])) {
                        if (i + 2 < args.length && !isReservedArg(args[i + 2])) {
                            save_from = args[++i];
                            save_to = args[++i];
                        } else {
                            save_to = args[++i];
                        }
                    }
                    break;
            }
        }
        if (fileName != null) {
            fat = new Fat();
            fat.open(new File(fileName));
        } else {
            System.err.println("Filename not specified!");
            return;
        }
        if (isNeeded(INFO)) {
            fat.print_info(info_path, info_depth, true);
        }
        if (isNeeded(LIST)) {
            fat.print_info(list_path, list_depth, false);
        }
        if (isNeeded(PRINT)) {
            fat.write(print_path, null);
        }
        if (isNeeded(SAVE)) {
            fat.write(save_from, save_to);
        }
        fat.close();
    }
    Fat fat;

    static class Fat {

        class LongPair {

            public long a, b;

            void set(long _a, long _b) {
                a = _a;
                b = _b;
            }
        }
        static final String[] keys = {"BS_jmpBoot", "BS_OEMName", "BPB_BytsPerSec", "BPB_SecPerClus", "BPB_RsvdSecCnt", "BPB_NumFATs", "BPB_RootEntCnt", "BPB_TotSec16", "BPB_Media", "BPB_FATSz16", "BPB_SecPerTrk", "BPB_NumHeads", "BPB_HiddSec", "BPB_TotSec32"};
        static final int[] keys_sz = {3, 8, 2, 1, 2, 1, 2, 2, 1, 2, 2, 2, 4, 4};
        static final String[] keys12_16 = {"BS_DrvNum", "BS_Reserved1", "BS_BootSig", "BS_VolID", "BS_VolLab", "BS_FilSysType"};
        static final int[] keys12_16_sz = {1, 1, 1, 4, 11, 8};
        static final String[] keys32 = {"BPB_FATSz32", "BPB_ExtFlags", "BPB_FSVer", "BPB_RootClus", "BPB_FSInfo", "BPB_BkBootSec", "BPB_Reserved", "BS_DrvNum", "BS_Reserved1", "BS_BootSig", "BS_VolID", "BS_VolLab", "BS_FilSysType"};
        static final int[] keys32_sz = {4, 2, 2, 4, 2, 2, 12, 1, 1, 1, 4, 11, 8};
        static final String[] keysFSInfo = {"FSI_LeadSig", "FSI_Reserved1", "FSI_StrucSig", "FSI_Free_Count", "FSI_Nxt_Free", "FSI_Reserved2", "FSI_TrailSig"};
        static final int[] keysFSInfo_sz = {4, 480, 4, 4, 4, 12, 4};
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

        void moveToClus(long clus) throws IOException {
            moveToClus(clus, 0);
        }

        void moveToClus(long clus, long offset) throws IOException {
            file.seek(getFirstSecOfClus(clus) * bytsPerSec + offset);
        }

        void readKeys(String[] keys, int[] keys_sz, HashMap<String, Long> props, HashMap<String, String> sprops) throws IOException {
            int summ = 0;
            for (int i = 0; i < keys.length; i++) {
                summ += keys_sz[i];
            }
            file.read(readBuffer, 0, summ);
            readKeysFromBuffer(readBuffer, keys, keys_sz, props, sprops);
        }

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

        static long getUnsignedIntFromBytes(byte[] bytes) {
            return getUnsignedIntFromBytes(bytes, 0);
        }

        static long getUnsignedIntFromBytes(byte[] bytes, int offset) {
            return getUnsignedIntFromBytes(bytes, offset, bytes.length - offset);
        }

        static long getUnsignedIntFromBytes(byte[] bytes, int offset, int length) {
            long val = 0;
            for (int j = offset; j < length + offset && j < bytes.length; j++) {
                val |= (0xFF & ((long) bytes[j])) << (8 * (j - offset));
            }
            val &= 0xFFFFFFFFL;
            return val;
        }

        long readNumber(int size) throws IOException {
            file.read(readBuffer, 0, size);
            return getUnsignedIntFromBytes(readBuffer, 0, size);
        }

        void readKeys(String[] keys, int[] keys_sz) throws IOException {
            readKeys(keys, keys_sz, props, sprops);
        }

        void moveToSec(long sector, long offset) throws IOException {
            file.seek(bytsPerSec * sector + offset);
        }

        void moveToSec(long sector) throws IOException {
            moveToSec(sector, 0);
        }

        long getNextClus(long clusId) throws IOException {
            long fatOffset;
            if (type == 12) {
                fatOffset = clusId + clusId / 2;
            } else if (type == 16) {
                fatOffset = clusId * 2;
            } else {
                fatOffset = clusId * 4;
            }
            long fatSecNum = rsvdSecCnt + (fatOffset / bytsPerSec);
            long fatEntOffset = fatOffset % bytsPerSec;
            moveToSec(fatSecNum, fatEntOffset);
            long val;
            if (type == 12) {//12 bits
                val = readNumber(2);
                if ((clusId & 1) == 1) {
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

        public Fat() {
            readBuffer = new byte[1500];
        }

        void open(File _file) throws IOException {
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
            root = new DirectoryEntry();
            root.isRootDir = true;
            root.readRoot();
        }

        void close() throws IOException {
            file.close();
        }

        long getFirstSecOfClus(long clusId) {
            return ((clusId - 2) * secPerClus) + firstDataSector;
        }

        long getClusOfSec(long sector) {
            sector -= firstDataSector;
            sector /= secPerClus;
            return sector + 2;
        }

        void print_info(String path, int depth, boolean verbose) throws IOException {
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
                de.print_info(depth, verbose);
            }
        }

        public class DirectoryEntry {

            final String[] deKeys = {"DIR_Name", "DIR_Attr", "DIR_NTRes", "DIR_CrtTimeTenth", "DIR_CrtTime", "DIR_CrtDate", "DIR_LstAccDate", "DIR_FstClusHI", "DIR_WrtTime", "DIR_WrtDate", "DIR_FstClusLO", "DIR_FileSize"};
            final int[] deKeys_sz = {11, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 4};
            HashMap<String, Long> deProps;
            HashMap<String, String> deSprops;
            int attributes;
            String shortName, longName;
            public boolean isRootDir = false;

            public boolean isDir() {
                return is(ATTR_DIRECTORY) || isRootDir;
            }

            public String getName() {
                if (longName == null || longName.isEmpty()) {
                    if (shortName == null || shortName.isEmpty()) {
                        return "No name";
                    }
                    return shortName;
                }
                return longName;
            }

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

            void readGeneralEntry(byte[] data) {
                deProps = new HashMap<>();
                deSprops = new HashMap<>();
                readKeysFromBuffer(data, deKeys, deKeys_sz, deProps, deSprops);
                shortName = deSprops.get("DIR_Name");
                attributes = deProps.get("DIR_Attr").intValue();
                if (!isRootDir) {
                    dataClus = ((deProps.get("DIR_FstClusHI") << 16) | deProps.get("DIR_FstClusLO"));
                }
            }

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

            public boolean readRootChild(long offset, LongPair lp) throws IOException {
                ArrayList<Byte> list = new ArrayList<>();
                //Going through long-name entries
                boolean started = false;
                while (true) {
                    if (offset + 32 > firstDataSector * bytsPerSec) {
                        return false;
                    }
                    file.read(readBuffer, 0, 32);
                    offset += 32;
                    if (!started) {
                        if (readBuffer[0] == ((byte) 0xE5)) {
                            continue;
                        } else if (readBuffer[0] == 0) {
                            lp.a = -1;
                            return false;
                        } else {
                            if (readBuffer[0] == ((byte) 0x05)) {
                                readBuffer[0] = (byte) 0xE5;
                            }
                            started = true;
                        }
                    }
                    attributes = (int) getUnsignedIntFromBytes(readBuffer, 11, 1);
                    if (!is(ATTR_LONG_NAME)) {
                        break;
                    }
                    readLNEntry(readBuffer, list);
                }
                lp.a = offset;
                readEntry(readBuffer, list);
                return true;
            }

            public boolean read(long clus, long offset, LongPair lp) throws IOException {
                ArrayList<Byte> list = new ArrayList<>();
                //Going through long-name entries
                boolean started = false;
                while (true) {
                    int read = readBytes(clus, offset, readBuffer, 0, 32, lp);
                    clus = lp.a;
                    offset = lp.b;
                    if (!started) {
                        if (readBuffer[0] == ((byte) 0xE5)) {
                            continue;
                        } else if (readBuffer[0] == 0) {
                            lp.a = -1;
                            return false;
                        } else {
                            if (readBuffer[0] == ((byte) 0x05)) {
                                readBuffer[0] = (byte) 0xE5;
                            }
                            started = true;
                        }
                    }
                    attributes = (int) getUnsignedIntFromBytes(readBuffer, 11, 1);
                    if (read < 32 || !is(ATTR_LONG_NAME)) {
                        break;
                    }
                    readLNEntry(readBuffer, list);
                }
                if (clus < 0) {
                    return false;
                }
                readEntry(readBuffer, list);
                return true;
            }

            public void readEntry(byte[] generalEntry, ArrayList<Byte> list) throws UnsupportedEncodingException {
                byte[] lnameBytes = new byte[list.size()];
                for (int i = 0; i < lnameBytes.length; i++) {
                    lnameBytes[i] = list.get(lnameBytes.length - 1 - i);
                }
                longName = new String(lnameBytes, "utf-16");
                readGeneralEntry(generalEntry);
            }

            public void readRoot() throws IOException {
                if (type == 32) {
                    dataClus = props.get("BPB_RootClus");
                    moveToClus(dataClus);
                } else {
                    moveToSec(fatSz * numFATs + rsvdSecCnt);
                }
                file.read(readBuffer, 0, 32);
                readGeneralEntry(readBuffer);
            }
            public long dataClus;
            public final int ATTR_READ_ONLY = 0x01;
            public final int ATTR_HIDDEN = 0x02;
            public final int ATTR_SYSTEM = 0x04;
            public final int ATTR_VOLUME_ID = 0x08;
            public final int ATTR_DIRECTORY = 0x10;
            public final int ATTR_ARCHIVE = 0x20;
            public final int ATTR_LONG_NAME = ATTR_READ_ONLY | ATTR_HIDDEN | ATTR_SYSTEM | ATTR_VOLUME_ID;
            public final String DOT_SHORTNAME = ".          ";
            public final String DOTDOT_SHORTNAME = "..         ";
            TreeMap<String, DirectoryEntry> children = null;

            public void retrieveChildren() throws IOException {
                if (children == null) {
                    children = new TreeMap<>();
                    if (!isDir()) {
                        return;
                    }
                    LongPair lp = new LongPair();
                    DirectoryEntry newChild = new DirectoryEntry();
                    if (isRootDir && type != 32) {
                        long offset = fatSz * numFATs + rsvdSecCnt + 32;
                        while (offset != -1) {
                            if (newChild.readRootChild(offset, lp)) {
                                children.put(newChild.getName(), newChild);
                                newChild = new DirectoryEntry();
                            }
                            offset = lp.a;
                        }
                    } else {
                        long clus = dataClus;
                        long offset = isRootDir ? 32 : 0;
                        while (clus != -1) {
                            if (newChild.read(clus, offset, lp)) {
                                children.put(newChild.getName(), newChild);
                                newChild = new DirectoryEntry();
                            }
                            clus = lp.a;
                            offset = lp.b;
                        }
                    }
                }
            }

            public boolean is(int attr) {
                return (attributes & attr) == attr;
            }

            public void print_info() throws IOException {
                print_info(0, false);
            }

            public void print_info(int childrenPrintDepth, boolean verbose) throws IOException {
                print_info(childrenPrintDepth, verbose, 0);
            }

            public void print_indent(int k) {
                for (int i = 0; i < k; i++) {
                    System.out.print("   ");
                }
            }

            public void print_info(int childrenPrintDepth, boolean verbose, int indent) throws IOException {
                if (verbose) {
                    print_indent(indent);
                    System.out.println("----------------------");
                    print_indent(indent);
                    System.out.println("Short name: " + shortName);
                    print_indent(indent);
                    System.out.println("Long name: " + longName);
                    print_indent(indent);
                    System.out.println("Read only: " + is(ATTR_READ_ONLY));
                    print_indent(indent);
                    System.out.println("Hidden: " + is(ATTR_HIDDEN));
                    print_indent(indent);
                    System.out.println("System: " + is(ATTR_SYSTEM));
                    print_indent(indent);
                    System.out.println("Directory: " + is(ATTR_DIRECTORY));
                    print_indent(indent);
                    System.out.println("Archive: " + is(ATTR_ARCHIVE));
                    print_indent(indent);
                    System.out.println("Size (KB = 2^10 Bytes): " + (deProps.get("DIR_FileSize") / Math.pow(2, 10)));
                    print_indent(indent);
                    System.out.println("Size (KB = 10^3 Bytes): " + (deProps.get("DIR_FileSize") / Math.pow(10, 3)));
                } else {
                    print_indent(indent);
                    System.out.println(getName());
                }
                retrieveChildren();
                if (verbose && isDir()) {
                    print_indent(indent);
                    System.out.println("Children count: " + getRealChildrenCount());
                }
                if (isDir() && getRealChildrenCount() > 0 && (childrenPrintDepth > indent)) {
                    if (verbose) {
                        print_indent(indent);
                        System.out.println("Children:");
                    }
                    Iterator<String> it = children.navigableKeySet().iterator();
                    do {
                        String key = it.next();
                        if (!children.get(key).shortName.equals(DOT_SHORTNAME)
                                && !children.get(key).shortName.equals(DOTDOT_SHORTNAME)) {
                            children.get(key).print_info(childrenPrintDepth, verbose, indent + 1);
                        }
                    } while (it.hasNext());
                }
            }

            private void write(File dest) throws IOException {
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
                    byte[] buffer = new byte[(int) (bytsPerClus)];
                    long clus = dataClus;
                    long fSize = deProps.get("DIR_FileSize");
                    while (fSize > 0) {
                        moveToClus(clus);
                        file.read(buffer, 0, (int) Math.min(fSize, (long) buffer.length));
                        out.write(buffer, 0, (int) Math.min(fSize, (long) buffer.length));
                        fSize -= buffer.length;
                        clus = getNextClus(clus);
                    }
                    if (dest != null) {
                        out.close();
                    }
                }
            }

            private int getRealChildrenCount() {
                return (children.size() - (isRootDir ? 0 : 2));
            }
        }
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        FatMaster fatMaster = new FatMaster(args);
    }
}
