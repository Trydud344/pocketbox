/*
 * Minimal libc prelude used to build upstream talloc for Android with the NDK,
 * bypassing samba's lib/replace/replace.h (which assumes autotools/waf probing).
 * Only the few macros talloc.c actually needs are provided here.
 */
#ifndef PB_TALLOC_PRELUDE_H
#define PB_TALLOC_PRELUDE_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <unistd.h>
#include <errno.h>
#include <ctype.h>
#include <limits.h>
#include <time.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

#ifndef _PUBLIC_
#define _PUBLIC_ __attribute__((visibility("default")))
#endif
#ifndef _PRIVATE_
#define _PRIVATE_ static
#endif
#ifndef _DEPRECATED_
#define _DEPRECATED_ __attribute__((deprecated))
#endif

#ifndef discard_const_p
#define discard_const_p(type, ptr) ((type *)(uintptr_t)(ptr))
#endif
#ifndef discard_const
#define discard_const(ptr) ((void *)(uintptr_t)(ptr))
#endif

/* Normally generated as version.h by waf */
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 2

#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif
#ifndef MAX
#define MAX(a, b) ((a) > (b) ? (a) : (b))
#endif

#endif /* PB_TALLOC_PRELUDE_H */
