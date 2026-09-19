#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

static int create_memfd(const char* name) {
    return syscall(__NR_memfd_create, name, 0);
}

static int copy_to_memfd(int source_fd, int target_fd) {
    char buffer[65536];

    while (true) {
        ssize_t read_count = read(source_fd, buffer, sizeof(buffer));
        if (read_count == 0) {
            return 0;
        }
        if (read_count < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }

        ssize_t offset = 0;
        while (offset < read_count) {
            ssize_t written = write(target_fd, buffer + offset, read_count - offset);
            if (written < 0) {
                if (errno == EINTR) {
                    continue;
                }
                return -1;
            }
            offset += written;
        }
    }
}

int main(int argc, char** argv, char** envp) {
    if (argc < 2) {
        fprintf(stderr, "cloudflared runner: missing binary path\n");
        return 64;
    }

    const char* binary_path = argv[1];
    int source_fd = open(binary_path, O_RDONLY | O_CLOEXEC);
    if (source_fd < 0) {
        fprintf(stderr, "cloudflared runner: open failed: %s\n", strerror(errno));
        return 126;
    }

    int memfd = create_memfd("droid-mcp-cloudflared");
    if (memfd < 0) {
        fprintf(stderr, "cloudflared runner: memfd_create failed: %s\n", strerror(errno));
        close(source_fd);
        return 126;
    }

    if (copy_to_memfd(source_fd, memfd) != 0) {
        fprintf(stderr, "cloudflared runner: copy failed: %s\n", strerror(errno));
        close(source_fd);
        close(memfd);
        return 126;
    }

    close(source_fd);

    if (fchmod(memfd, 0700) != 0) {
        fprintf(stderr, "cloudflared runner: chmod failed: %s\n", strerror(errno));
        close(memfd);
        return 126;
    }

    if (lseek(memfd, 0, SEEK_SET) < 0) {
        fprintf(stderr, "cloudflared runner: seek failed: %s\n", strerror(errno));
        close(memfd);
        return 126;
    }

    char** child_argv = static_cast<char**>(calloc(static_cast<size_t>(argc), sizeof(char*)));
    if (child_argv == nullptr) {
        fprintf(stderr, "cloudflared runner: argv allocation failed\n");
        close(memfd);
        return 126;
    }

    for (int i = 1; i < argc; ++i) {
        child_argv[i - 1] = argv[i];
    }
    child_argv[argc - 1] = nullptr;

    fexecve(memfd, child_argv, envp);

    fprintf(stderr, "cloudflared runner: fexecve failed: %s\n", strerror(errno));
    free(child_argv);
    close(memfd);
    return 126;
}
