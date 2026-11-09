#include <jni.h>
#include <node.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <string>
#include <vector>
#include <unistd.h>

#define LOG(...) __android_log_print(ANDROID_LOG_ERROR, "DaurenBOT-Node", __VA_ARGS__)

// Node's libuv expects writable, contiguous argv. One Node runtime per Android :engine process.
extern "C" JNIEXPORT jint JNICALL
Java_kz_daurenbot_v11p0_EngineService_runNode(
        JNIEnv* env, jobject,
        jstring workJava, jstring stateJava, jstring tempJava, jstring secretJava) {
    if (!workJava || !stateJava || !tempJava || !secretJava) return -2;
    const char* work = env->GetStringUTFChars(workJava, nullptr);
    const char* state = env->GetStringUTFChars(stateJava, nullptr);
    const char* temp = env->GetStringUTFChars(tempJava, nullptr);
    const char* secret = env->GetStringUTFChars(secretJava, nullptr);

    if (!work || !state || !temp || !secret) {
        if (work) env->ReleaseStringUTFChars(workJava, work);
        if (state) env->ReleaseStringUTFChars(stateJava, state);
        if (temp) env->ReleaseStringUTFChars(tempJava, temp);
        if (secret) env->ReleaseStringUTFChars(secretJava, secret);
        return -3;
    }

    std::string cwd(work), stateDir(state), tempDir(temp), secretHex(secret);
    env->ReleaseStringUTFChars(workJava, work);
    env->ReleaseStringUTFChars(stateJava, state);
    env->ReleaseStringUTFChars(tempJava, temp);
    env->ReleaseStringUTFChars(secretJava, secret);

    if (chdir(cwd.c_str()) != 0) {
        LOG("Cannot chdir into runtime");
        return -4;
    }
    setenv("HOME", cwd.c_str(), 1);
    setenv("TMPDIR", tempDir.c_str(), 1);
    setenv("DAURENBOT_DATA_DIR", stateDir.c_str(), 1);
    if (secretHex.size() != 64) return -5;
    setenv("DAURENBOT_AUTH_SECRET_HEX", secretHex.c_str(), 1);
    std::fill(secretHex.begin(),secretHex.end(),'0');
    setenv("TZ", "Asia/Almaty", 1);
    setenv("NODE_OPTIONS", "--max-old-space-size=192 --unhandled-rejections=warn", 1);
    setenv("NODE_COMPILE_CACHE", tempDir.c_str(), 1);

    std::vector<std::string> arguments = {"node", "engine.js"};
    size_t bytes = 0;
    for (const auto& arg : arguments) bytes += arg.size() + 1;
    std::vector<char> buffer(bytes);
    std::vector<char*> argv;
    argv.reserve(arguments.size() + 1);
    char* at = buffer.data();
    for (const auto& arg : arguments) {
        argv.push_back(at);
        memcpy(at, arg.c_str(), arg.size() + 1);
        at += arg.size() + 1;
    }
    argv.push_back(nullptr);

    LOG("Starting embedded Node.js P0");
    int code = node::Start(static_cast<int>(arguments.size()), argv.data());
    LOG("Node exited with code %d", code);
    return code;
}
