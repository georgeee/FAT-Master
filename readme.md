FAT Master
---------------------
FAT Master is a tiny utilite, written as a task for the university.
It's only purpose is to read data from FAT volumes.

How to use:

java -jar [args]

Possible arguments:

-f FILE    -    open FAT volume, located in FILE (it can be either image or path to device like /dev/sdc1)

-i [PATH]  -    print information about PATH, or about the whole volume if it's not specified (it prints the whole directory tree, with information about every file)

-id NUM    -    specify the depth of directory tree, printed by "-i [PATH]"

-l [PATH]  -    print the directory tree of PATH, no information about files, only names

-ld NUM    -    specify the depth of directory tree, printed by "-l [PATH]"

-p PATH    -    prints file, specified by PATH

-s [PATH] DIR/FILE - saves directory/file, specified by path to directory/file from DIR/FILE

-h/--help  -    print this help