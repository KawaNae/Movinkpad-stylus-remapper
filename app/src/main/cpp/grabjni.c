#include <sys/ioctl.h>
#include <linux/input.h>
#include <poll.h>
#include <jni.h>

JNIEXPORT jint JNICALL
Java_com_example_stylusremapper_PenGrab_nativeGrab(JNIEnv* env, jclass clazz, jint fd, jboolean on) {
    return (jint) ioctl((int) fd, EVIOCGRAB, on ? (void*)1 : (void*)0);
}

JNIEXPORT jint JNICALL
Java_com_example_stylusremapper_PenGrab_nativePoll(JNIEnv* env, jclass clazz, jint fd, jint timeoutMs) {
    struct pollfd pfd = { .fd = (int) fd, .events = POLLIN };
    return (jint) poll(&pfd, 1, (int) timeoutMs);
}

JNIEXPORT jboolean JNICALL
Java_com_example_stylusremapper_PenGrab_nativeIsKeyPressed(JNIEnv* env, jclass clazz, jint fd, jint keyCode) {
    unsigned char key_states[(KEY_MAX + 7) / 8 + 1];
    if (ioctl((int) fd, EVIOCGKEY(sizeof(key_states)), key_states) < 0) return JNI_FALSE;
    return (key_states[keyCode / 8] & (1 << (keyCode % 8))) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_example_stylusremapper_PenGrab_nativeGetAbsInfo(JNIEnv* env, jclass clazz, jint fd, jint axis) {
    struct input_absinfo info;
    if (ioctl((int) fd, EVIOCGABS(axis), &info) < 0) return NULL;
    jintArray result = (*env)->NewIntArray(env, 3);
    jint vals[3] = { info.minimum, info.maximum, info.resolution };
    (*env)->SetIntArrayRegion(env, result, 0, 3, vals);
    return result;
}
