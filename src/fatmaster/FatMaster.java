/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package fatmaster;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 *
 * @author georgeee
 */
public class FatMaster {

    static final int INTERACTIVE_MODE = 1;
    static final int PRINT_FAT_INFO = (1 << 1);
    static final int EXECUTE_SCRIPT = (1 << 2);
    /**
     * Bit mask: 0 - Whether to use interactive mode or just execute the stuff
     * from args 1 - Indicates utilite to print fat image information 2 -
     * Indicates utilite to run the script
     */
    int runningMode = 0;

    private boolean isNeeded(int mode_mask) {
        return (runningMode & mode_mask) == runningMode;
    }
    String printFileName;

    class Fat {

        public class DirectoryEntry {

            final String[] deKeys = {"DIR_Name", "DIR_Attr", "DIR_NTRes", "DIR_CrtTimeTenth", "DIR_CrtTime", "DIR_CrtDate", "DIR_LstAccDate", "DIR_FstClusHI", "DIR_WrtTime", "DIR_WrtDate", "DIR_FstClusLO", "DIR_FileSize"};
            final int[] deKeys_sz = {11, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 4};
            HashMap<String, Long> deProps;
            HashMap<String, String> deSprops;
            int attributes;
            String shortName, longName;
            public boolean isRootDir = false;
            long sector, offset;

            void ensureClusterEnd() throws IOException {
                long bytsPerSec = props.get("BPB_BytsPerSec");
                long _sector = sector;
                if (offset >= bytsPerSec) {
                    sector += offset / bytsPerSec;
                    offset %= bytsPerSec;
                }
                if ((type == 32 || !isRootDir) && getClusIdOfSec(sector) != getClusIdOfSec(_sector)) {
                    sector = getNextClusId(getClusIdOfSec(sector));
                    if (sector >= 0) {
                        sector = firstSectorOfCluster(sector);
                    }
                }
            }

            public void read(long clusId) throws IOException {
                read(firstSectorOfCluster(clusId), 0);
            }

            public void read(long _sector, long _offset) throws IOException {
                sector = _sector;
                offset = _offset;
                deProps = new HashMap<>();
                deSprops = new HashMap<>();
                ArrayList<Byte> list = new ArrayList<>();
                int _attrs = ATTR_LONG_NAME;
                //Going through long-name entries
                while ((_attrs & ATTR_LONG_NAME) == ATTR_LONG_NAME) {
                    moveToSec(sector, offset + 11);
                    _attrs = (int) readVal(1);
                    if ((_attrs & ATTR_LONG_NAME) == ATTR_LONG_NAME) {
                        moveToSec(sector, offset);
                        byte[] buffer = new byte[32];
                        file.read(buffer, 0, 32);
                        for (int i = 31; i >= 28; i -= 2) {
                            byte s1 = buffer[i - 1];
                            byte s2 = buffer[i];
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
                            byte s1 = buffer[i - 1];
                            byte s2 = buffer[i];
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
                            byte s1 = buffer[i - 1];
                            byte s2 = buffer[i];
                            if (s1 == -1 && s2 == -1) {
                                continue;
                            }
                            if (s1 == 0 && s2 == 0) {
                                continue;
                            }

                            list.add(s1);
                            list.add(s2);
                        }
                        offset += 32;
                        ensureClusterEnd();
                    }
                }
                byte[] lnameBytes = new byte[list.size()];
                for (int i = 0; i < lnameBytes.length; i++) {
                    lnameBytes[i] = list.get(lnameBytes.length - 1 - i);
                }
                longName = new String(lnameBytes, "utf-16");
                moveToSec(sector, offset);
                readKeys(deKeys, deKeys_sz, deProps, deSprops);
                offset += 32;
                ensureClusterEnd();
                shortName = deSprops.get("DIR_Name");
                attributes = deProps.get("DIR_Attr").intValue();
                dataClusId = ((deProps.get("DIR_FstClusHI") << 16) & deProps.get("DIR_FstClusLO"));
            }

            public void renewPointers(long clusId) {
                sector = firstSectorOfCluster(clusId);
                System.out.println(shortName+": "+(sector*props.get("BPB_BytsPerSec")));
                offset = 0;
            }
            public long dataClusId;
            public final int ATTR_READ_ONLY = 0x01;
            public final int ATTR_HIDDEN = 0x02;
            public final int ATTR_SYSTEM = 0x04;
            public final int ATTR_VOLUME_ID = 0x08;
            public final int ATTR_DIRECTORY = 0x10;
            public final int ATTR_ARCHIVE = 0x20;
            public final int ATTR_LONG_NAME = ATTR_READ_ONLY | ATTR_HIDDEN | ATTR_SYSTEM | ATTR_VOLUME_ID;
            public final String DOT_SHORTNAME = ".\0\0\0\0\0\0\0\0\0\0";
            ArrayList<DirectoryEntry> children = null;

            public void retrieveChildren() throws IOException {
                if (children == null) {
                    children = new ArrayList<>();
                    while (sector >= 0) {
                        moveToSec(sector, offset);
                        long fByte = readVal(1);
                        if (fByte == 0xE5) {
                            offset += 32;
                            ensureClusterEnd();
                            continue;
                        }
                        if (fByte == 0) {
                            break;
                        }
                        DirectoryEntry child = new DirectoryEntry();
                        child.read(sector, offset);
                        sector = child.sector;
                        offset = child.offset;
                        if (child.is(ATTR_DIRECTORY)) {
                            child.renewPointers(child.dataClusId);
                        }
                        children.add(child);
                    }
                }
            }

            public boolean is(int attr) {
                return (attributes & attr) == attr;
            }

            public void print_info() throws IOException {
                print_info(true, true, 0);
            }

            public void print_indent(int k) {
                for (int i = 0; i < k; i++) {
                    System.out.print("   ");
                }
            }

            public void print_info(boolean printChildren, boolean printChildrenRecursive, int indent) throws IOException {
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
                if (printChildren && (is(ATTR_DIRECTORY)||isRootDir)) {
                    print_indent(indent);
                    retrieveChildren();
                    if(children.size()>0) System.out.println("Children:");
                    else System.out.println("Directory is empty");
                    for (int i = 0; i < children.size(); i++) {
                        if (!children.get(i).shortName.equals(
                                DOT_SHORTNAME)) {
                            children.get(i).print_info(printChildrenRecursive, printChildrenRecursive, indent + 1);
                        }
                    }
                }
            }
        }
        /**
         * 12 - fat12, 16 - fat16, 32 - fat32
         */
        short type;
        long EOC;
        final String[] keys = {"BS_jmpBoot", "BS_OEMName", "BPB_BytsPerSec", "BPB_SecPerClus", "BPB_RsvdSecCnt", "BPB_NumFATs", "BPB_RootEntCnt", "BPB_TotSec16", "BPB_Media", "BPB_FATSz16", "BPB_SecPerTrk", "BPB_NumHeads", "BPB_HiddSec", "BPB_TotSec32"};
        final int[] keys_sz = {3, 8, 2, 1, 2, 1, 2, 2, 1, 2, 2, 2, 4, 4};
        final String[] keys12_16 = {"BS_DrvNum", "BS_Reserved1", "BS_BootSig", "BS_VolID", "BS_VolLab", "BS_FilSysType"};
        final int[] keys12_16_sz = {1, 1, 1, 4, 11, 8};
        final String[] keys32 = {"BPB_FATSz32", "BPB_ExtFlags", "BPB_FSVer", "BPB_RootClus", "BPB_FSInfo", "BPB_BkBootSec", "BPB_Reserved", "BS_DrvNum", "BS_Reserved1", "BS_BootSig", "BS_VolID", "BS_VolLab", "BS_FilSysType"};
        final int[] keys32_sz = {4, 2, 2, 4, 2, 2, 12, 1, 1, 1, 4, 11, 8};
        final String[] keysFSInfo = {"FSI_LeadSig", "FSI_Reserved1", "FSI_StrucSig", "FSI_Free_Count", "FSI_Nxt_Free", "FSI_Reserved2", "FSI_TrailSig"};
        final int[] keysFSInfo_sz = {4, 480, 4, 4, 4, 12, 4};
        RandomAccessFile file = null;
        HashMap<String, Long> props;
        HashMap<String, String> sprops;
        DirectoryEntry root;
        byte[] buffer = null;

        void readKeys(String[] keys, int[] keys_sz, HashMap<String, Long> props, HashMap<String, String> sprops) throws IOException {
            if (buffer == null) {
                buffer = new byte[500];
            }
            for (int i = 0; i < keys.length; i++) {
                file.read(buffer, 0, keys_sz[i]);
                if (keys_sz[i] < 5) {
                    props.put(keys[i], getLongFromBytes(buffer, 0, keys_sz[i]));
                } else {
                    sprops.put(keys[i], new String(buffer, 0, keys_sz[i]));
                }
            }
        }

        long getLongFromBytes(byte[] bytes) {
            return getLongFromBytes(bytes, 0);
        }

        long getLongFromBytes(byte[] bytes, int offset) {
            return getLongFromBytes(bytes, offset, bytes.length - offset);
        }

        long getLongFromBytes(byte[] bytes, int offset, int length) {
            long val = 0;
            for (int j = offset; j < length + offset && j < bytes.length; j++) {
                val |= (0xFF & ((long) bytes[j])) << (8 * (j));
            }
            val &= 0xFFFFFFFFL;
            return val;
        }

        long readVal(int size) throws IOException {
            byte _buffer[] = new byte[size];
            file.read(_buffer, 0, size);
            return getLongFromBytes(_buffer);
        }

        void readKeys(String[] keys, int[] keys_sz) throws IOException {
            readKeys(keys, keys_sz, props, sprops);
        }
        long rootDirSectors, fatSz, totSec, dataSec, countOfClusters, firstDataSector, freeSpace, totSpace;

        void moveToSec(long secId, long offset) throws IOException {
            file.seek(props.get("BPB_BytsPerSec") * secId + offset);
        }

        void moveToSec(long secId) throws IOException {
            moveToSec(secId, 0);
        }

        long getNextClusId(long clusId) throws IOException {
            long fatOffset;
            if (type == 12) {
                fatOffset = clusId + clusId / 2;
            } else if (type == 16) {
                fatOffset = clusId * 2;
            } else {
                fatOffset = clusId * 4;
            }
            long fatSecNum = props.get("BPB_RsvdSecCnt") + (fatOffset / props.get("BPB_BytsPerSec"));
            long fatEntOffset = fatOffset % props.get("BPB_BytsPerSec");
            moveToSec(fatSecNum, fatEntOffset);
            long val;
            if (type == 12) {//12 bits
                val = readVal(2);
                if ((clusId & 1) == 1) {
                    val >>= 4;
                } else {
                    val &= 0x0FFF;
                }
            } else if (type == 16) {//16 bits
                val = readVal(2);
            } else {//28 bits
                val = readVal(4) & 0x0FFFFFFFL;
            }
            if (val == EOC) {
                return -1;
            }
            return val;
        }

        void open(File _file) throws IOException {
            if (file != null) {
                file.close();
            }
            file = new RandomAccessFile(_file, "rw");
            props = new HashMap<String, Long>();
            sprops = new HashMap<String, String>();

            readKeys(keys, keys_sz);

            //Exploring the type of our FAT
            rootDirSectors = ((props.get("BPB_RootEntCnt") * 32) + (props.get("BPB_BytsPerSec") - 1)) / props.get("BPB_BytsPerSec");
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
            dataSec = totSec - (props.get("BPB_RsvdSecCnt") + (fatSz * props.get("BPB_NumFATs")) + rootDirSectors);
            countOfClusters = dataSec / props.get("BPB_SecPerClus");
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

            firstDataSector = props.get("BPB_RsvdSecCnt") + (props.get("BPB_NumFATs") * fatSz) + rootDirSectors;
            freeSpace = -1;
            if (type == 32) {
                moveToSec(props.get("BPB_FSInfo"));
                readKeys(keysFSInfo, keysFSInfo_sz);
                if (props.get("FSI_Free_Count") != 0xFFFFFFFFL) {
                    freeSpace = props.get("FSI_Free_Count") * props.get("BPB_SecPerClus") * props.get("BPB_BytsPerSec");
                }
            }

            if (freeSpace == -1) {
                moveToSec(props.get("BPB_RsvdSecCnt"));
                freeSpace = 0;
                for (int i = 0; i < countOfClusters; i++) {
                    if (getNextClusId(i) == 0) {
                        freeSpace++;
                    }
                }
                freeSpace *= props.get("BPB_SecPerClus") * props.get("BPB_BytsPerSec");
            }
            totSpace = countOfClusters * props.get("BPB_SecPerClus") * props.get("BPB_BytsPerSec");
            root = new DirectoryEntry();
            root.isRootDir = true;
            if (type == 32) {
                root.read(props.get("BPB_RootClus"));
            } else {
                root.read(fatSz * props.get("BPB_NumFATs") + props.get("BPB_RsvdSecCnt"), 0);
            }
        }

        long firstSectorOfCluster(long clusId) {
            return ((clusId - 2) * props.get("BPB_SecPerClus")) + firstDataSector;
        }

        long getClusIdOfSec(long sector) {
            sector -= firstDataSector;
            sector /= props.get("BPB_SecPerClus");
            return sector + 2;
        }

        void close() throws IOException {
            file.close();
        }

        void print_info(String path) throws IOException {
            if (path == null) {
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
                root.print_info();
            }
        }

        void print_info() throws IOException {
            print_info(null);
        }
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        new FatMaster(args);
    }

    private FatMaster(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-p")) {
                printFileName = args[++i];
                runningMode ^= (1 << 1);
            }
        }
        if (isNeeded(PRINT_FAT_INFO)) {
            Fat f = new Fat();
            f.open(new File(printFileName));
            f.print_info();
            f.close();
        }
    }
}
