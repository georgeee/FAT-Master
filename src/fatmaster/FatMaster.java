package fatmaster;

import java.io.File;
import java.io.IOException;

/**
 * @author georgeee
 * Main class of this tiny utilite, serves as an interface
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
    final String[] reservedArgs = {"-f", "-i", "-p", "-l", "-ld", "-id", "-s", "-h", "--help"};

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
                case "-h":
                case "--help":
                    System.out.println("FAT Master - very simple utilite to read FAT partitions\n"
                            + "It was created in november 2012 by George Agapov (george.agapov@gmail.com) as a task for the university\n"
                            + "How to use:\n"
                            + "java -jar [args]\n"
                            + "Possible arguments:\n"
                            + "-f FILE    -    open FAT partition, located in FILE (it can be either image or path to device like /dev/sdc1)\n"
                            + "-i [PATH]  -    print information about PATH, or about the whole partition if it's not specified (it prints the whole directory tree, with information about every file)\n"
                            + "-id NUM    -    specify the depth of directory tree, printed by \"-i [PATH]\"\n"
                            + "-l [PATH]  -    print the directory tree of PATH, no information about files, only names\n"
                            + "-ld NUM    -    specify the depth of directory tree, printed by \"-l [PATH]\"\n"
                            + "-p PATH    -    prints file, specified by PATH\n"
                            + "-s [PATH] DIR/FILE - saves directory/file, specified by path to directory/file from DIR/FILE\n"
                            + "-h/--help  -    print this help");
                    return;
//                    break;
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
            fat.printInfo(info_path, info_depth, true);
        }
        if (isNeeded(LIST)) {
            fat.printInfo(list_path, list_depth, false);
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


    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        FatMaster fatMaster = new FatMaster(args);
    }
}
