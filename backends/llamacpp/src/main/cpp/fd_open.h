// Force-included into ggml.c only, where ggml_fopen calls fopen. A path "fd:N" opens a duplicate of the open file
// descriptor N: a model the user picked from storage is readable through the descriptor the system gave the app, but
// not by its path, which needs a storage permission the app does not have. Every other path goes to fopen.
#pragma once

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static inline FILE *den_fopen(const char *path, const char *mode) {
    if (strncmp(path, "fd:", 3) == 0) {
        int fd = dup(atoi(path + 3));
        if (fd < 0) return NULL;
        FILE *f = fdopen(fd, mode);
        if (!f) close(fd);
        // The descriptor is shared with the app: start from the beginning whatever was read before.
        else fseek(f, 0, SEEK_SET);
        return f;
    }
    return fopen(path, mode);
}

#define fopen den_fopen
