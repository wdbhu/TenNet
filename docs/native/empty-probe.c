#include <android/log.h>
__attribute__((constructor))
static void init(void) {
    __android_log_print(ANDROID_LOG_INFO, "TunNet", "空库已加载（仅测试）");
}
