#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>

/* No shell or privilege change. Installed alongside the owned native tools. */
int main(int argc, char **argv) {
    if (argc < 2 || argc > 258) return 110;
    char self[PATH_MAX], tool[PATH_MAX];
    ssize_t size=readlink("/proc/self/exe",self,sizeof(self)-1);
    if (size<=0) return 111;
    self[size]='\0';
    if (!realpath(argv[1],tool)) return 112;
    char *self_slash=strrchr(self,'/'), *tool_slash=strrchr(tool,'/');
    if (!self_slash || !tool_slash) return 113;
    if (strcmp(tool_slash+1,"libffmpeg-cli.so") && strcmp(tool_slash+1,"libffprobe-cli.so")) return 114;
    *self_slash='\0'; *tool_slash='\0';
    if (strcmp(self,tool)) return 115;
    *tool_slash='/';
    const char *pidfile=getenv("AF_MEDIA_PID_FILE");
    if (!pidfile || strlen(pidfile)>=PATH_MAX) return 116;
    int fd=open(pidfile,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if (fd<0) return 117;
    char pid[32];int length=snprintf(pid,sizeof(pid),"%ld\n",(long)getpid());
    if (length<=0 || write(fd,pid,(size_t)length)!=length || close(fd)) return 118;
    struct rlimit file_limit={256ULL*1024*1024,256ULL*1024*1024};
    struct rlimit cpu_limit={1800,1801};
    if (setrlimit(RLIMIT_FSIZE,&file_limit) || setrlimit(RLIMIT_CPU,&cpu_limit)) return 119;
    unsetenv("LD_PRELOAD");
    execv(tool,&argv[1]);
    return errno==ENOENT ? 120 : 121;
}
