#include <stdio.h>		/* rename(2), */
#include <stdlib.h>		/* atoi */
#include <unistd.h>		/* symlink(2), symlinkat(2), readlink(2),
				 * lstat(2), unlink(2), unlinkat(2) */
#include <string.h>		/* str*, strrchr, strcat, strcpy, strncpy,
				 * strncmp */
#include <sys/types.h>		/* lstat(2), */
#include <sys/stat.h>		/* lstat(2), */
#include <errno.h>		/* E*, */
#include <fcntl.h>		/* AT_*, O_TMPFILE, */
#include <limits.h>		/* PATH_MAX, */

#include "extension/extension.h"
#include "tracee/tracee.h"
#include "tracee/mem.h"
#include "syscall/syscall.h"
#include "syscall/sysnum.h"
#include "path/path.h"
#include "arch.h"
#include "attribute.h"

#define PREFIX ".l2s."
#define DELETED_SUFFIX " (deleted)"

/**
 * Copy the contents of the @symlink into @value (nul terminated).
 * This function returns -errno if an error occured, otherwise 0.
 */
static int my_readlink(const char symlink[PATH_MAX], char value[PATH_MAX])
{
    ssize_t size;

    size = readlink(symlink, value, PATH_MAX);
    if (size < 0)
	return -(errno != 0 ? errno : EIO);
    if (size >= PATH_MAX)
	return -ENAMETOOLONG;
    value[size] = '\0';

    return 0;
}

/**
 * Move the path pointed to by @tracee's @sysarg to a new location,
 * symlink the original path to this new one, make @tracee's @sysarg
 * point to the new location.  This function returns -errno if an
 * error occured, otherwise 0.
 */
static int move_and_symlink_path(Tracee *tracee, Reg sysarg)
{
    char original[PATH_MAX];
    char intermediate[PATH_MAX];
    char new_intermediate[PATH_MAX];
    char final[PATH_MAX];
    char new_final[PATH_MAX];
    char *name;
    struct stat statl;
    ssize_t size;
    int status;
    int link_count;
    int first_link = 1;
    int intermediate_suffix = 1;

    /* Note: this path was already canonicalized.  */
    size =
	read_string(tracee, original,
		    peek_reg(tracee, CURRENT, sysarg), PATH_MAX);
    if (size < 0)
	return size;
    if (size >= PATH_MAX)
	return -ENAMETOOLONG;

    /* Do not attempt to move/symlink procfs paths or empty AT_EMPTY_PATH. */
    if (original[0] == '\0' || strncmp(original, "/proc/", 6) == 0)
	return -EXDEV;

    /* Sanity check: directories can't be linked.  */
    status = lstat(original, &statl);
    if (status < 0)
	return -(errno != 0 ? errno : EIO);
    if (S_ISDIR(statl.st_mode))
	return -EPERM;

    /* Check if it is a symbolic link.  */
    if (S_ISLNK(statl.st_mode)) {
	/* get name */
	size = my_readlink(original, intermediate);
	if (size < 0)
	    return size;

	name = strrchr(intermediate, '/');
	if (name == NULL)
	    name = intermediate;
	else
	    name++;

	if (strncmp(name, PREFIX, strlen(PREFIX)) == 0)
	    first_link = 0;
    } else {
	name = strrchr(original, '/');
	if (name == NULL)
	    name = original;
	else
	    name++;

	if (strncmp(name, PREFIX, strlen(PREFIX)) == 0) {
	    size_t orig_len = strlen(original);
	    if (orig_len > 5 && original[orig_len - 5] == '.') {
		char check_final[PATH_MAX];
		struct stat inter_stat;

		memcpy(intermediate, original, orig_len - 5);
		intermediate[orig_len - 5] = '\0';
		if (lstat(intermediate, &inter_stat) == 0
		    && S_ISLNK(inter_stat.st_mode)
		    && my_readlink(intermediate, check_final) == 0
		    && strcmp(check_final, original) == 0) {
		    first_link = 0;
		}
	    }
	}

	if (first_link) {
	    /* compute new name */
	    if (strlen(PREFIX) + strlen(original) + 10 >= PATH_MAX)
		return -ENAMETOOLONG;

	    strncpy(intermediate, original, strlen(original) - strlen(name));
	    intermediate[strlen(original) - strlen(name)] = '\0';
	    strcat(intermediate, PREFIX);
	    strcat(intermediate, name);
	}
    }

    if (first_link) {
	/* Move the original content to the new path. */
	do {
	    snprintf(new_intermediate, PATH_MAX, "%s%04d", intermediate,	/* NOSONAR: bounded by PATH_MAX */
		     intermediate_suffix);
	    intermediate_suffix++;
	} while ((access(new_intermediate, F_OK) != -1)
		 && (intermediate_suffix < 1000));
	strcpy(intermediate, new_intermediate);

	strcpy(final, intermediate);
	strcat(final, ".0002");
	status = rename(original, final);
	if (status < 0)
	    return -(errno != 0 ? errno : EIO);

	/* Symlink the intermediate to the final file.  */
	status = symlink(final, intermediate);
	if (status < 0)
	    return -(errno != 0 ? errno : EIO);

	/* Symlink the original path to the intermediate one.  */
	status = symlink(intermediate, original);
	if (status < 0)
	    return -(errno != 0 ? errno : EIO);
    } else {
	/*
	 * Move the original content to new location, by incrementing
	 * count at end of path.
	 */
	size = my_readlink(intermediate, final);
	if (size < 0)
	    return size;

	link_count = atoi(final + strlen(final) - 4);
	link_count++;

	strncpy(new_final, final, strlen(final) - 4);
	snprintf(new_final + strlen(final) - 4, 5, "%04d", link_count);

	status = rename(final, new_final);
	if (status < 0)
	    return -(errno != 0 ? errno : EIO);
	strcpy(final, new_final);
	/* Symlink the intermediate to the final file.  */
	status = unlink(intermediate);
	if (status < 0)
	    return -(errno != 0 ? errno : EIO);
	status = symlink(final, intermediate);
	if (status < 0)
	    return -(errno != 0 ? errno : EIO);
    }

    status = set_sysarg_path(tracee, intermediate, sysarg);
    if (status < 0)
	return status;

    return 0;
}


/*
 * If path points a file that is a symlink to a file that begins with PREFIX,
 * let the file be deleted, but also delete the symlink that was created and
 * decremnt the count that is tacked to end of original file.
 */
static int decrement_link_count(Tracee *tracee, Reg sysarg)
{
    char original[PATH_MAX];
    char intermediate[PATH_MAX];
    char final[PATH_MAX];
    char new_final[PATH_MAX];
    char *name;
    struct stat statl;
    ssize_t size;
    int status;
    int link_count;

    /* Note: this path was already canonicalized.  */
    size =
	read_string(tracee, original,
		    peek_reg(tracee, CURRENT, sysarg), PATH_MAX);
    if (size < 0)
	return size;
    if (size >= PATH_MAX)
	return -ENAMETOOLONG;

    /* Check if it is a converted link already.  */
    status = lstat(original, &statl);
    if (status < 0)
	return 0;

    if (!S_ISLNK(statl.st_mode))
	return 0;

    size = my_readlink(original, intermediate);
    if (size < 0)
	return size;

    name = strrchr(intermediate, '/');
    if (name == NULL)
	name = intermediate;
    else
	name++;

    /* Check if an l2s file is pointed to */
    if (strncmp(name, PREFIX, strlen(PREFIX)) != 0)
	return 0;

    size = my_readlink(intermediate, final);
    if (size < 0)
	return size;

    link_count = atoi(final + strlen(final) - 4);
    link_count--;

    /* Check if it is or is not the last link to delete */
    if (link_count > 0) {
	strncpy(new_final, final, strlen(final) - 4);
	snprintf(new_final + strlen(final) - 4, 5, "%04d", link_count);

	status = rename(final, new_final);
	if (status < 0)
	    return -(errno != 0 ? errno : EIO);

	strcpy(final, new_final);

	/* Symlink the intermediate to the final file.  */
	status = unlink(intermediate);
	if (status < 0)
	    return -(errno != 0 ? errno : EIO);

	status = symlink(final, intermediate);
	if (status < 0)
	    return -(errno != 0 ? errno : EIO);
    } else {
	/* If it is the last, delete the intermediate and final */
	status = unlink(intermediate);
	if (status < 0)
	    return -(errno != 0 ? errno : EIO);
	status = unlink(final);
	if (status < 0)
	    return -(errno != 0 ? errno : EIO);
    }

    return 0;
}

/**
 * Make it so fake hard links look like real hard link with respect to number of links and inode
 * This function returns -errno if an error occured, otherwise 0.
 */
static int handle_sysexit_end(Tracee *tracee)
{
    word_t sysnum;

    sysnum = get_sysnum(tracee, ORIGINAL);

    switch (sysnum) {

    case PR_fstatat64:
	//int fstatat(int dirfd, const char *pathname, struct stat *buf, int flags);
    case PR_newfstatat:
	//int fstatat(int dirfd, const char *pathname, struct stat *buf, int flags);
    case PR_stat64:
	//int stat(const char *path, struct stat *buf);
    case PR_lstat64:
	//int lstat(const char *path, struct stat *buf);
    case PR_fstat64:
	//int fstat(int fd, struct stat *buf);
    case PR_stat:
	//int stat(const char *path, struct stat *buf);
    case PR_statx:
	//int statx(int fd, const char *path, unsigned flags, unsigned mask, struct statx *buf);
    case PR_lstat:
	//int lstat(const char *path, struct stat *buf);
    case PR_fstat:{
	    //int fstat(int fd, struct stat *buf);
	    word_t result;
	    Reg sysarg_stat;
	    Reg sysarg_path;
	    int status;
	    struct stat statl;
	    ssize_t size;
	    char original[PATH_MAX];
	    char intermediate[PATH_MAX];
	    char final[PATH_MAX];
	    char *name;
	    struct stat finalStat;

	    /* Override only if it succeed.  */
	    result = peek_reg(tracee, CURRENT, SYSARG_RESULT);
	    if (result != 0)
		return 0;

	    if (sysnum == PR_fstat64 || sysnum == PR_fstat) {
		size_t orig_len;
		size_t suffix_len = strlen(DELETED_SUFFIX);

		status =
		    readlink_proc_pid_fd(tracee->pid,
					 peek_reg(tracee,
						  MODIFIED,
						  SYSARG_1), original);
		if (status < 0)
		    return status;
		orig_len = strlen(original);
		if (orig_len >= suffix_len
		    && strcmp(original + orig_len - suffix_len,
			      DELETED_SUFFIX) == 0)
		    original[orig_len - suffix_len] = '\0';
	    } else {
		if (sysnum == PR_fstatat64
		    || sysnum == PR_newfstatat || sysnum == PR_statx)
		    sysarg_path = SYSARG_2;
		else
		    sysarg_path = SYSARG_1;
		size =
		    read_string(tracee, original,
				peek_reg(tracee, MODIFIED,
					 sysarg_path), PATH_MAX);
		if (size < 0)
		    return size;
		if (size >= PATH_MAX)
		    return -ENAMETOOLONG;
	    }

	    name = strrchr(original, '/');
	    if (name == NULL)
		name = original;
	    else
		name++;

	    /* Check if it is a link */
	    status = lstat(original, &statl);
	    if (status < 0)
		return 0;

	    if (strncmp(name, PREFIX, strlen(PREFIX)) == 0) {
		if (S_ISLNK(statl.st_mode)) {
		    strcpy(intermediate, original);
		    goto intermediate_proc;
		} else {
		    strcpy(final, original);
		    goto final_proc;
		}
	    }

	    if (!S_ISLNK(statl.st_mode))
		return 0;

	    size = my_readlink(original, intermediate);
	    if (size < 0)
		return size;

	    name = strrchr(intermediate, '/');
	    if (name == NULL)
		name = intermediate;
	    else
		name++;

	    if (strncmp(name, PREFIX, strlen(PREFIX)) != 0)
		return 0;

	  intermediate_proc:
	    size = my_readlink(intermediate, final);
	    if (size < 0)
		return size;

	  final_proc:
	    status = lstat(final, &finalStat);
	    if (status < 0)
		return -(errno != 0 ? errno : EIO);

	    if (sysnum == PR_statx) {
		word_t address = peek_reg(tracee, ORIGINAL, SYSARG_5);
		uint32_t nlink = (uint32_t) atoi(final + strlen(final) - 4);
		uint16_t stx_mode = peek_uint16(tracee, address + 28);
		if (errno != 0)
		    return -errno;
		if (S_ISLNK(stx_mode)) {
		    poke_uint32(tracee, address + 4, (uint32_t) finalStat.st_blksize);
		    if (errno != 0) return -errno;
		    poke_uint32(tracee, address + 20, (uint32_t) finalStat.st_uid);
		    if (errno != 0) return -errno;
		    poke_uint32(tracee, address + 24, (uint32_t) finalStat.st_gid);
		    if (errno != 0) return -errno;
		    poke_uint16(tracee, address + 28, (uint16_t) finalStat.st_mode);
		    if (errno != 0) return -errno;
		    poke_uint64(tracee, address + 32, (uint64_t) finalStat.st_ino);
		    if (errno != 0) return -errno;
		    poke_uint64(tracee, address + 40, (uint64_t) finalStat.st_size);
		    if (errno != 0) return -errno;
		    poke_uint64(tracee, address + 48, (uint64_t) finalStat.st_blocks);
		    if (errno != 0) return -errno;
		}
		poke_uint32(tracee, address + 16, nlink);
		if (errno != 0)
		    return -errno;
		return 0;
	    }

	    finalStat.st_nlink = atoi(final + strlen(final) - 4);

	    /* Get the address of the 'stat' structure.  */
	    if (sysnum == PR_fstatat64 || sysnum == PR_newfstatat)
		sysarg_stat = SYSARG_3;
	    else
		sysarg_stat = SYSARG_2;

	    status =
		write_data(tracee,
			   peek_reg(tracee, ORIGINAL,
				    sysarg_stat), &finalStat,
			   sizeof(finalStat));
	    if (status < 0)
		return status;

	    return 0;
	}

    default:
	return 0;
    }
}

/**
 * When @translated_path is a faked hard-link, replace it with the
 * point it (internally) points to.
 */
static void translated_path(char translated_path[PATH_MAX])
{
    char path2[PATH_MAX];
    char path[PATH_MAX];
    char *component;
    int status;

    status = my_readlink(translated_path, path);
    if (status < 0)
	return;

    component = strrchr(path, '/');
    if (component == NULL)
	return;
    component++;

    if (strncmp(component, PREFIX, strlen(PREFIX)) != 0)
	return;

    status = my_readlink(path, path2);
    if (status < 0)
	return;

#if 0				/* Sanity check. */
    component = strrchr(path, '/');
    if (component == NULL)
	return;
    component++;

    if (strncmp(component, PREFIX, strlen(PREFIX)) != 0)
	return;
#endif

    strcpy(translated_path, path2);
    return;
}

/**
 * Handler for this @extension.  It is triggered each time an @event
 * occurred.  See ExtensionEvent for the meaning of @data1 and @data2.
 */
int
link2symlink_callback(Extension *extension, ExtensionEvent event,
		      intptr_t data1, intptr_t data2 UNUSED)
{
    int status;

    switch (event) {
    case INITIALIZATION:{
	    /* List of syscalls handled by this extensions.  */
	    static FilteredSysnum filtered_sysnums[] = {
		{
		 PR_link, FILTER_SYSEXIT },
		{
		 PR_linkat, FILTER_SYSEXIT },
		{
		 PR_unlink, FILTER_SYSEXIT },
		{
		 PR_unlinkat, FILTER_SYSEXIT },
		{
		 PR_fstat, FILTER_SYSEXIT },
		{
		 PR_fstat64, FILTER_SYSEXIT },
		{
		 PR_fstatat64, FILTER_SYSEXIT },
		{
		 PR_lstat, FILTER_SYSEXIT },
		{
		 PR_lstat64, FILTER_SYSEXIT },
		{
		 PR_newfstatat, FILTER_SYSEXIT },
		{
		 PR_stat, FILTER_SYSEXIT },
		{
		 PR_statx, FILTER_SYSEXIT },
		{
		 PR_stat64, FILTER_SYSEXIT },
		{
		 PR_rename, FILTER_SYSEXIT },
		{
		 PR_renameat, FILTER_SYSEXIT },
		{
		 PR_renameat2, FILTER_SYSEXIT },
		{
		 PR_open, 0 },
		{
		 PR_openat, 0 },
		FILTERED_SYSNUM_END,
	    };
	    extension->filtered_sysnums = filtered_sysnums;
	    return 0;
	}

    case SYSCALL_ENTER_END:{
	    Tracee *tracee = TRACEE(extension);

	    switch (get_sysnum(tracee, ORIGINAL)) {
#ifdef O_TMPFILE
	    case PR_open:
	    case PR_openat:
		if ((int) data1 >= 0) {
		    word_t sysnum = get_sysnum(tracee, ORIGINAL);
		    word_t flags = peek_reg(tracee, ORIGINAL,
					    sysnum == PR_open
					    ? SYSARG_2 : SYSARG_3);
		    if ((flags & O_TMPFILE) == O_TMPFILE)
			return -EOPNOTSUPP;
		}
		break;
#endif
	    case PR_rename:
		/*
		 * int rename(const char *oldpath, const char
		 * *newpath); If newpath is a psuedo hard
		 * link decrement the link count.
		 */

		status = decrement_link_count(tracee, SYSARG_2);
		if (status < 0)
		    return status;

		break;

	    case PR_renameat:
	    case PR_renameat2:
		/*
		 * int renameat(int olddirfd, const char
		 * *oldpath, int newdirfd, const char
		 * *newpath); If newpath is a psuedo hard
		 * link decrement the link count.
		 */

		status = decrement_link_count(tracee, SYSARG_4);
		if (status < 0)
		    return status;

		break;

	    case PR_unlink:
		/*
		 * If path points a file that is an symlink
		 * to a file that begins with PREFIX, let the
		 * file be deleted, but also decrement the
		 * hard link count, if it is greater than 1,
		 * otherwise delete the original file and
		 * intermediate file too.
		 */

		status = decrement_link_count(tracee, SYSARG_1);
		if (status < 0)
		    return status;

		break;

	    case PR_unlinkat:
		/*
		 * If path points a file that is a symlink to
		 * a file that begins with PREFIX, let the
		 * file be deleted, but also delete the
		 * symlink that was created and decremnt the
		 * count that is tacked to end of original
		 * file.
		 */

		status = decrement_link_count(tracee, SYSARG_2);
		if (status < 0)
		    return status;

		break;

	    case PR_link:
		/*
		 * Convert:
		 *
		 * int link(const char *oldpath, const char
		 * *newpath);
		 *
		 * into:
		 *
		 * int symlink(const char *oldpath, const
		 * char *newpath);
		 */

		status = move_and_symlink_path(tracee, SYSARG_1);
		if (status < 0)
		    return status;

		set_sysnum(tracee, PR_symlink);
		break;

	    case PR_linkat:
		/*
		 * Convert:
		 *
		 * int linkat(int olddirfd, const char
		 * *oldpath, int newdirfd, const char
		 * *newpath, int flags);
		 *
		 * into:
		 *
		 * int symlinkat(const char *oldpath, int
		 * newdirfd, const char *newpath);
		 *
		 * Note: PRoot has already canonicalized
		 * linkat() paths this way:
		 *
		 * olddirfd + oldpath -> oldpath
		 * newdirfd + newpath -> newpath
		 *
		 * Use PR_symlinkat instead of PR_symlink because
		 * architecture-independent ABIs like arm64 have no
		 * legacy symlink(2) syscall.
		 */

		status = move_and_symlink_path(tracee, SYSARG_2);
		if (status < 0)
		    return status;

		poke_reg(tracee, SYSARG_1,
			 peek_reg(tracee, CURRENT, SYSARG_2));
		poke_reg(tracee, SYSARG_2, AT_FDCWD);
		poke_reg(tracee, SYSARG_3,
			 peek_reg(tracee, CURRENT, SYSARG_4));

		set_sysnum(tracee, PR_symlinkat);
		break;

	    default:
		break;
	    }
	    return 0;
	}

    case SYSCALL_EXIT_END:{
	    return handle_sysexit_end(TRACEE(extension));
	}

    case TRANSLATED_PATH:{
	    Tracee *tracee = TRACEE(extension);
	    word_t sysnum = get_sysnum(tracee, ORIGINAL);

	    /* Do not dereference .l2s. symlinks to their backing file for
	     * link/unlink/rename syscalls, which must act on the link
	     * entry itself. */
	    switch (sysnum) {
	    case PR_link:
	    case PR_linkat:
	    case PR_unlink:
	    case PR_unlinkat:
	    case PR_rename:
	    case PR_renameat:
	    case PR_renameat2:
		return 0;
	    default:
		translated_path((char *) data1);
		return 0;
	    }
	}

    default:
	return 0;
    }
}
