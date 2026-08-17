#include <jni.h>
#include <errno.h>
#include <sys/stat.h>
#include <time.h>

JNIEXPORT jint JNICALL
Java_com_aurafiles_app_data_NativeFileTime_setModifiedNative(
        JNIEnv *env,
        jobject instance,
        jint descriptor,
        jlong timestamp_millis) {
    (void) env;
    (void) instance;

    struct stat current;
    if (fstat(descriptor, &current) != 0) {
        return errno;
    }

    struct timespec times[2];
    times[0].tv_sec = current.st_atime;
    times[0].tv_nsec = 0;
    times[1].tv_sec = timestamp_millis / 1000;
    times[1].tv_nsec = (timestamp_millis % 1000) * 1000000;

    if (futimens(descriptor, times) != 0) {
        return errno;
    }

    return 0;
}

