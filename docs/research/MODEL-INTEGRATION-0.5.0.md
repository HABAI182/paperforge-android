# 0.5.0 模型接入

用户提供APK及REA恢复资料，模型清单见[随包记录](../../app/src/main/assets/scan/provenance.json)。本轮只接入边缘/位移两份初始化缓冲，以及可读预处理、显示跟踪和网格核心。没有加载原竞品.so、壳、账号或网络代码。

推理库为[官方MNN3.6.1](https://github.com/alibaba/MNN/tree/d407447ed56c4121a11ccbd266dc184ca1ead0c2)。CMake固定源码归档及SHA256；可用`-PpaperforgeMnnSource=/absolute/path/to/MNN`指定同版本本地源码。CPU双线程、高精度，关闭GPU/KleidiAI/SME2。MNN的Apache-2.0不构成第三方权重的开源授权；权重原始训练数据和开放许可未建立。

| 阶段 | 实现与边界 |
|---|---|
| 纸边输入 | RGBA/RGB→BGR；长边不超过420，再线性缩至240平方，除255；依据cardCut/to_tensor |
| 纸边输出 | 第一通道多阈值闭运算、四边形拟合、四边支持度选优；PaperForge后处理，未声称等同原EDLine线组合 |
| 静态照片 | 保留预览框沿用与原图精修 |
| 显示跟踪 | 双候选、EMA、260ms超时、可调替换帧数；显式使用本应用毫秒，未照搬原未知tick单位 |
| 曲面输入 | BGR；短边≥1024先缩1024平方，≥352再缩352平方，最后240平方；依据to_tensor_dewarp |
| 位移输出 | 两通道、39×39高斯、加坐标、轴归一、放大映射并重采样；get_warp_mat/get_dewarp_map核心，加入强度混合 |

原库mask/curve分支、完整质量判定及加固Java调用参数未全量接入。曲面由用户预览后明确应用，旧图由历史恢复，OCR按新revision重识别。

一张现有实拍照片本地检查了纸边覆盖，三种自有合成背景在Android CPU运行。没有新真机画质或真实弯曲书页质量结论，不声称完整原应用等价。
