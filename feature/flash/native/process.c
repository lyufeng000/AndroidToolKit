/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

static char **strings(JNIEnv *env, jobjectArray input) {
    int count = (*env)->GetArrayLength(env, input);
    char **result = calloc(count + 1, sizeof(char *));
    if (!result) return NULL;
    for (int i = 0; i < count; ++i) {
        jstring value = (*env)->GetObjectArrayElement(env, input, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        result[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
    }
    return result;
}
static void release(char **data) {
    if (data) { for (int i = 0; data[i]; ++i) free(data[i]); free(data); }
}

JNIEXPORT jintArray JNICALL Java_com_lyufeng_flash_NativeProcess_spawn(
        JNIEnv *env, jobject self, jobjectArray arguments, jobjectArray environment, jint usbfd) {
    char **argv = strings(env, arguments), **envp = strings(env, environment);
    int pipes[2], result = ENOMEM;
    pid_t pid = -1;
    if (argv && envp && pipe2(pipes, O_CLOEXEC) == 0) {
        /* posix_spawn is only available from API 28. The child uses only
         * async-signal-safe calls before execve so this also works on API 24. */
        pid_t parent_pid = getpid();
        pid = fork();
        if (pid == 0) {
            prctl(PR_SET_PDEATHSIG, SIGKILL);
            if (getppid() != parent_pid) _exit(127);
            setpgid(0, 0);
            dup2(pipes[1], STDOUT_FILENO);
            dup2(pipes[1], STDERR_FILENO);
            close(pipes[0]); close(pipes[1]);
            if (usbfd >= 0) { dup2(usbfd, 198); fcntl(198, F_SETFD, 0); }
            execve(argv[0], argv, envp);
            _exit(127);
        }
        result = pid < 0 ? errno : 0;
        if (pid > 0) setpgid(pid, pid);
        close(pipes[1]);
        if (result) close(pipes[0]);
    }
    release(argv); release(envp);
    jint values[2] = { result ? -result : pid, result ? -1 : pipes[0] };
    jintArray output = (*env)->NewIntArray(env, 2);
    (*env)->SetIntArrayRegion(env, output, 0, 2, values);
    return output;
}

JNIEXPORT jint JNICALL Java_com_lyufeng_flash_NativeProcess_waitFor(JNIEnv *env, jobject self, jint pid) {
    int status, result;
    do { result = waitpid(pid, &status, 0); } while (result < 0 && errno == EINTR);
    if (result < 0) return -errno;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}
JNIEXPORT void JNICALL Java_com_lyufeng_flash_NativeProcess_terminate(JNIEnv *env, jobject self, jint pid) {
    if (pid > 0 && kill(-pid, SIGKILL) < 0 && errno == ESRCH) kill(pid, SIGKILL);
}
