#include <jni.h>
#include <memory>
#include <vector>

#include "Logging.h"
#include "OpenWakeWordEngine.h"

static constexpr char LOG_TAG[] = "OpenWakeWord_jni";

static const uint8_t* bufferData(JNIEnv* env, jobject buffer, size_t* size) {
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (data == nullptr || capacity <= 0) {
        LOGE(LOG_TAG, "Model ByteBuffer is not a non-empty direct buffer");
        return nullptr;
    }
    *size = static_cast<size_t>(capacity);
    return data;
}

static jlong nativeCreate(
    JNIEnv* env, jclass /*clazz*/, jobject melspectrogramBuffer, jobject embeddingBuffer,
    jobject classifierBuffer, jfloat probabilityCutoff, jboolean resetOnDetection) {

    size_t melspectrogramSize = 0;
    size_t embeddingSize = 0;
    size_t classifierSize = 0;
    const uint8_t* melspectrogram = bufferData(env, melspectrogramBuffer, &melspectrogramSize);
    const uint8_t* embedding = bufferData(env, embeddingBuffer, &embeddingSize);
    const uint8_t* classifier = bufferData(env, classifierBuffer, &classifierSize);
    if (melspectrogram == nullptr || embedding == nullptr || classifier == nullptr) return 0;

    auto engine = std::make_unique<OpenWakeWordEngine>(
        melspectrogram, melspectrogramSize,
        embedding, embeddingSize,
        classifier, classifierSize,
        static_cast<float>(probabilityCutoff),
        resetOnDetection == JNI_TRUE
    );

    if (!engine->isInitialized()) {
        return 0;
    }

    return reinterpret_cast<jlong>(engine.release());
}

static jboolean nativeProcessAudio(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jshortArray samplesArray) {

    if (handle == 0) return JNI_FALSE;

    auto* engine = reinterpret_cast<OpenWakeWordEngine*>(handle);

    jsize numSamples = env->GetArrayLength(samplesArray);
    jshort* samples = env->GetShortArrayElements(samplesArray, nullptr);
    if (samples == nullptr) return JNI_FALSE;

    bool detected = engine->processAudio(samples, static_cast<size_t>(numSamples), nullptr);

    env->ReleaseShortArrayElements(samplesArray, samples, JNI_ABORT);

    return detected ? JNI_TRUE : JNI_FALSE;
}

static jfloatArray nativeScores(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jshortArray samplesArray) {

    std::vector<float> scores;
    if (handle != 0) {
        auto* engine = reinterpret_cast<OpenWakeWordEngine*>(handle);
        jsize numSamples = env->GetArrayLength(samplesArray);
        jshort* samples = env->GetShortArrayElements(samplesArray, nullptr);
        if (samples != nullptr) {
            (void) engine->processAudio(samples, static_cast<size_t>(numSamples), &scores);
            env->ReleaseShortArrayElements(samplesArray, samples, JNI_ABORT);
        }
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(scores.size()));
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(scores.size()), scores.data());
    }
    return result;
}

static void nativeReset(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0) return;
    auto* engine = reinterpret_cast<OpenWakeWordEngine*>(handle);
    engine->reset();
}

static void nativeDestroy(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0) return;
    auto* engine = reinterpret_cast<OpenWakeWordEngine*>(handle);
    delete engine;
}

static const JNINativeMethod methods[] = {
    {"nativeCreate", "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;FZ)J",
     reinterpret_cast<void*>(nativeCreate)},
    {"nativeProcessAudio", "(J[S)Z", reinterpret_cast<void*>(nativeProcessAudio)},
    {"nativeScores", "(J[S)[F", reinterpret_cast<void*>(nativeScores)},
    {"nativeReset", "(J)V", reinterpret_cast<void*>(nativeReset)},
    {"nativeDestroy", "(J)V", reinterpret_cast<void*>(nativeDestroy)},
};

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("dev/heyari/ari/wakeword/OpenWakeWord");
    if (clazz == nullptr) {
        LOGE(LOG_TAG, "Failed to find OpenWakeWord class for JNI registration");
        return JNI_ERR;
    }

    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        LOGE(LOG_TAG, "Failed to register native methods");
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
