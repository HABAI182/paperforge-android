#include <jni.h>
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <memory>
#include <vector>

namespace {
struct Model {
    std::vector<unsigned char> bytes;
    std::unique_ptr<MNN::Interpreter> interpreter;
    MNN::Session* session = nullptr;
    MNN::Tensor* input = nullptr;
    MNN::Tensor* output = nullptr;
};
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_cn_paperforge_app_imaging_ScanModel_nativeCreate(JNIEnv* env, jobject, jbyteArray data) {
    auto model = std::make_unique<Model>();
    const auto length = env->GetArrayLength(data);
    model->bytes.resize(length);
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(model->bytes.data()));
    if (env->ExceptionCheck()) return 0;
    model->interpreter.reset(MNN::Interpreter::createFromBuffer(model->bytes.data(), length));
    if (!model->interpreter) { fail(env, "Cannot load scan model"); return 0; }
    MNN::ScheduleConfig config;
    config.type = MNN_FORWARD_CPU;
    config.numThread = 2;
    MNN::BackendConfig backend;
    backend.precision = MNN::BackendConfig::Precision_High;
    config.backendConfig = &backend;
    model->session = model->interpreter->createSession(config);
    if (!model->session) { fail(env, "Cannot create scan session"); return 0; }
    model->input = model->interpreter->getSessionInput(model->session, nullptr);
    model->output = model->interpreter->getSessionOutput(model->session, nullptr);
    if (!model->input || !model->output || model->input->batch() != 1 ||
        model->input->channel() != 3 || model->input->width() != 240 || model->input->height() != 240 ||
        model->output->channel() != 2 || model->output->width() != 240 || model->output->height() != 240) {
        fail(env, "Unexpected scan model shape"); return 0;
    }
    return reinterpret_cast<jlong>(model.release());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_cn_paperforge_app_imaging_ScanModel_nativeRun(JNIEnv* env, jobject, jlong handle, jfloatArray data) {
    auto* model = reinterpret_cast<Model*>(handle);
    if (!model || env->GetArrayLength(data) != 3 * 240 * 240) {
        fail(env, "Invalid scan input"); return nullptr;
    }
    MNN::Tensor hostInput(model->input, MNN::Tensor::CAFFE);
    env->GetFloatArrayRegion(data, 0, 3 * 240 * 240, hostInput.host<float>());
    if (env->ExceptionCheck()) return nullptr;
    model->input->copyFromHostTensor(&hostInput);
    if (model->interpreter->runSession(model->session) != MNN::NO_ERROR) {
        fail(env, "Scan inference failed"); return nullptr;
    }
    MNN::Tensor hostOutput(model->output, MNN::Tensor::CAFFE);
    model->output->copyToHostTensor(&hostOutput);
    auto result = env->NewFloatArray(2 * 240 * 240);
    if (result) env->SetFloatArrayRegion(result, 0, 2 * 240 * 240, hostOutput.host<float>());
    return result;
}
