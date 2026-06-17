#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/input.h>

/* EVIOCGRAB the given input device to take it away from Android's InputReader,
   hold for N seconds, then release. Verifies whether removing the real pen from
   the input stream lets injected stylus events drive the CSP canvas. */
int main(int argc, char** argv) {
    if (argc < 2) { fprintf(stderr, "usage: grab <device> [holdSec]\n"); return 2; }
    int hold = argc > 2 ? atoi(argv[2]) : 8;
    int fd = open(argv[1], O_RDONLY);
    if (fd < 0) { perror("open"); return 1; }
    if (ioctl(fd, EVIOCGRAB, (void*)1) < 0) { perror("EVIOCGRAB grab"); close(fd); return 1; }
    fprintf(stderr, "grabbed %s for %d sec\n", argv[1], hold);
    fflush(stderr);
    sleep(hold);
    ioctl(fd, EVIOCGRAB, (void*)0);
    close(fd);
    fprintf(stderr, "released\n");
    return 0;
}
