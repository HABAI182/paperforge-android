// Original PaperForge CPU pixel compositor. No model inference or proprietary code here.
#include <jni.h>
#include <cstdint>
#include <algorithm>
#include <cmath>
#include <vector>

namespace {
struct Bytes {
    JNIEnv* env; jbyteArray array; jbyte* data;
    Bytes(JNIEnv* e, jbyteArray a) : env(e), array(a), data(a ? e->GetByteArrayElements(a, nullptr) : nullptr) {}
    ~Bytes() { if (data) env->ReleaseByteArrayElements(array, data, JNI_ABORT); }
    unsigned char operator[](int i) const { return static_cast<unsigned char>(data[i]); }
};
struct Ints {
    JNIEnv* env; jintArray array; jint* data;
    Ints(JNIEnv* e, jintArray a) : env(e), array(a), data(e->GetIntArrayElements(a, nullptr)) {}
    ~Ints() { if (data) env->ReleaseIntArrayElements(array, data, JNI_ABORT); }
    jint operator[](int i) const { return data[i]; }
};
// Measure colour relative to the bright paper, so warm paper is not protected as orange ink.
// Bounded sampling avoids a per-pixel illumination estimator and excludes saturated/dark objects.
struct PaperTint {
    int r=0,g=0,b=0;
    int chroma(int red,int green,int blue) const {
        return std::max({red-r,green-g,blue-b})-std::min({red-r,green-g,blue-b});
    }
};
PaperTint paperTint(const Bytes& rgb,int pixels) {
    int hist[3][256]={},count=0;
    const int step=std::max(1,pixels/16000);
    for(int i=0;i<pixels;i+=step) {
        const int r=rgb[i*3],g=rgb[i*3+1],b=rgb[i*3+2];
        if(std::min({r,g,b})<120 || std::max({r,g,b})-std::min({r,g,b})>80 || r+10<b || g+10<b) continue;
        ++hist[0][r];++hist[1][g];++hist[2][b];++count;
    }
    if(count<64) return {};
    int level[3]={};
    for(int c=0;c<3;++c) {
        int sum=0;
        for(int v=0;v<256;++v) if((sum+=hist[c][v])>=count*.8f) { level[c]=v;break; }
    }
    const int luma=(level[0]+2*level[1]+level[2])/4;
    return {level[0]-luma,level[1]-luma,level[2]-luma};
}
float clamp(float v) { return std::clamp(v, 0.f, 1.f); }
jbyte byte(float v) { return static_cast<jbyte>(std::clamp(static_cast<int>(std::floor(v + .5f)), 0, 255)); }
void invalid(JNIEnv* env) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Paper plane size mismatch"); }
jbyteArray output(JNIEnv* env, const std::vector<jbyte>& bytes) {
    auto array = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (array) env->SetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()), bytes.data());
    return array;
}
}
// Evidence flags mirror WorkbookContent. This is a source guard, not an OCR/semantic classifier.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_paperforge_app_imaging_PaperPixelEngine_componentProtection(JNIEnv* env,jobject,
    jbyteArray ink,jbyteArray soft,jintArray labels,jintArray stats,jint w,jint h,jfloatArray params) {
    const int n=env->GetArrayLength(ink),count=env->GetArrayLength(stats)/5;
    if (static_cast<int64_t>(w)*h!=n || w<1 || h<1 || env->GetArrayLength(soft)!=n ||
        env->GetArrayLength(labels)!=n || count<1 || env->GetArrayLength(stats)!=count*5 || env->GetArrayLength(params)!=4) {invalid(env);return nullptr;}
    Bytes c(env,ink),low(env,soft);Ints ids(env,labels),regions(env,stats);
    if (!c.data || !low.data || !ids.data || !regions.data) return nullptr;
    float p[4];env->GetFloatArrayRegion(params,0,4,p);
    std::vector<int> anchors(count,0), peak(count,0);
    std::vector<int64_t> energy(count,0);
    for (int i=0;i<n;++i) if (ids[i]>0) {
        peak[ids[i]]=std::max(peak[ids[i]],static_cast<int>(c[i]));
        energy[ids[i]]+=c[i];
        // Weak but crisp pencil needs a lower absolute anchor than printed dark ink.
        const float edgeFloor=c[i]<50 ? std::max(2.f,c[i]*std::clamp(p[3],.12f,.4f)) : std::max(p[0],c[i]*p[2]);
        if (c[i]>=80 || c[i]-low[i]>=edgeFloor) ++anchors[ids[i]];
    }
    std::vector<unsigned char> protect(count,0);
    const int tiny=std::max(12,n/100000),shortSide=std::min(w,h);
    for (int id=1;id<count;++id) {
        const int width=regions[id*5+2],height=regions[id*5+3],area=regions[id*5+4];
        // Tiny isolated components are ambiguous punctuation, never automatically noise.
        if (area<=tiny || anchors[id]>=area*std::clamp(p[1],.1f,.5f)) protect[id]=1;
        // Separate observed edge anchors from the ambiguous tiny-component guard. Strong
        // cleanup can suppress diffuse isolated marks without deleting sharp punctuation.
        if (anchors[id]>=std::max(1.f,area*std::clamp(p[1],.1f,.5f))) protect[id]|=8;
        // Whole observed strokes with distributed dark/sharp anchors may gain contrast.
        // Tiny punctuation remains protected, but is not evidence for large contrast gains.
        const bool softStroke=std::max(width,height)<shortSide*.12 && std::min(width,height)>=2 &&
            area<width*height*.80 && energy[id]>=area*35;
        if (area>tiny && peak[id]>=60 && (anchors[id]>=area*std::clamp(p[1],.1f,.5f) || softStroke)) protect[id]|=4;
        // Grey diagrams/fills and long thin observed strokes deserve their own protection.
        if ((std::min(width,height)>shortSide*.025 && area>width*height*.5) ||
            (std::max(width,height)>shortSide*.12 && std::min(width,height)<=4)) protect[id]=3;
    }
    std::vector<jbyte> result(n);
    for (int i=0;i<n;++i) result[i]=static_cast<jbyte>(protect[ids[i]]);
    return output(env,result);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_cn_paperforge_app_imaging_PaperPixelEngine_protectedWorkbook(JNIEnv* env, jobject,
    jbyteArray rgb, jbyteArray ink, jbyteArray soft, jbyteArray rules, jbyteArray candidate, jbyteArray support, jbyteArray background,
    jint w, jint h, jfloatArray params) {
    const int n=env->GetArrayLength(ink);
    if (w<1 || h<1 || static_cast<int64_t>(w)*h!=n || env->GetArrayLength(rgb)!=n*3 ||
        env->GetArrayLength(soft)!=n || env->GetArrayLength(rules)!=n ||
        env->GetArrayLength(candidate)!=n || env->GetArrayLength(support)!=n || env->GetArrayLength(background)!=n || env->GetArrayLength(params)!=13) {invalid(env);return nullptr;}
    Bytes color(env,rgb), c(env,ink), low(env,soft), line(env,rules), tone(env,candidate), context(env,support), blank(env,background);
    if (!color.data || !c.data || !low.data || !line.data || !tone.data || !context.data || !blank.data) return nullptr;
    float p[13]; env->GetFloatArrayRegion(params,0,13,p);
    const PaperTint tint=paperTint(color,n);
    constexpr int PRINT=1,FAINT=2,HORIZONTAL=4,VERTICAL=8,FIGURE=16,BACKGROUND=32,BLEED=64,UNCERTAIN=128;
    // Only distributed long rules delimit eligible bands; no band is itself a whitening mask.
    std::vector<int> rowSupport(h,0), rows;
    for (int y=0;y<h;++y) for (int x=0;x<w;++x) if (line[y*w+x]>=6) ++rowSupport[y];
    for (int y=0;y<h;++y) if (rowSupport[y]>w*.45) {
        if (rows.empty() || y-rows.back()>std::max(3,h/300)) rows.push_back(y);
    }
    std::vector<bool> band(h,false);
    if (rows.size()>=3) for (size_t k=1;k<rows.size();++k) {
        const int gap=rows[k]-rows[k-1];
        if (gap>=6 && gap<=h*.20) for (int y=rows[k-1]+2;y<rows[k]-2;++y) band[y]=true;
    }
    std::vector<jbyte> rendered(n), labels(n), bleed(n);
    const int policy=std::clamp(static_cast<int>(p[0]),0,2);
    const float minimum[3]={std::clamp(p[6],.5f,1.f),std::clamp(p[7],.3f,1.f),std::clamp(p[8],.2f,1.f)};
    for (int y=0;y<h;++y) for (int x=0;x<w;++x) {
        const int i=y*w+x, v=c[i], blurred=low[i];
        int lo=v,hi=v, horizontal=0,vertical=0;
        for (int dy=-1;dy<=1;++dy) for (int dx=-1;dx<=1;++dx) {
            const int j=std::clamp(y+dy,0,h-1)*w+std::clamp(x+dx,0,w-1);
            lo=std::min(lo,static_cast<int>(c[j])); hi=std::max(hi,static_cast<int>(c[j]));
        }
        for (int d=-2;d<=2;++d) {
            horizontal+=c[y*w+std::clamp(x+d,0,w-1)];
            vertical+=c[std::clamp(y+d,0,h-1)*w+x];
        }
        const int r=color[i*3],g=color[i*3+1],b=color[i*3+2];
        const bool chroma=tint.chroma(r,g,b)>=p[3] && v>=4;
        const bool ruling=line[i]>=4 && v>=3;
        const bool sharp=context[i]!=0 || (v>=4 && v-blurred>=std::max(4.f,v*p[5]));
        const bool dark=v>=80;
        // A smooth dark patch might be a diagram/fill, never a removable background component.
        const bool figure=(context[i]&2) || (v>=65 && lo>=v*.72f);
        const bool suspected=v>=3 && v<=p[4] && !ruling && !sharp && !chroma && blurred>=v*.65f;
        int mask=0;
        if (dark && !figure && !ruling && !chroma) mask|=PRINT;
        if ((v>=3 && !dark) || chroma) mask|=FAINT;
        if (ruling) {
            if (horizontal>=vertical*.8f) mask|=HORIZONTAL;
            if (vertical>=horizontal*.8f) mask|=VERTICAL;
        }
        if (figure) mask|=FIGURE;
        if (v<3) mask|=BACKGROUND;
        if (suspected) mask|=BLEED;
        // All faint/coloured content and boundaries are uncertain, even if the model disagrees.
        if (v>=3 && (!dark || chroma)) mask|=UNCERTAIN;
        float retention=std::clamp(p[1],.8f,1.f);
        if (ruling || sharp || chroma || figure || dark) retention=1.f;
        else if (suspected && band[y]) retention=minimum[policy];
        else if (suspected) retention=std::clamp(p[9],.5f,1.f);
        if (p[10] >= .5f) retention=1.f;
        // A single sharp punctuation pixel is preserved, never deleted by component area.
        float kept=std::max(255.f-tone[i],v*retention);
        // A foreground candidate is not evidence that smooth source bleed is dark ink.
        // Bound its tone to the selected source retention instead of re-darkening the mask.
        if (suspected) kept=std::min(kept,v*retention);
        // Positive whole-stroke evidence is needed to strengthen defocused front ink. The old
        // model/tone candidate alone often assigns little confidence to the first soft formula.
        // A continuous contrast knee preserves its antialiasing and cannot create new support.
        if (context[i]&4) kept=std::max(kept,v*(1.f+(std::clamp(p[11],1.f,2.6f)-1.f)*clamp((v-20.f)/50.f)));
        // Avoid crushed pencil tones and noise hallucinated by a learned candidate.
        const float sourceGain=(context[i]&4) ? (1.f+(std::clamp(p[11],1.f,2.6f)-1.f)*clamp((v-20.f)/70.f))
            : (v<120 ? std::clamp(p[2],1.f,1.3f) : 1.55f);
        const float ceiling=std::min(255.f,v*sourceGain);
        kept=std::min(kept,ceiling);
        // Enrich supported stroke interiors without darkening the observed outer edge.
        // No dilation: thin glyph borders keep their measured source contrast.
        if(p[12]>.5f && lo<v*.5f) kept=std::min(kept,static_cast<float>(v));
        if (v<3) kept=0.f;
        if (blank[i] && p[10]<.5f && !ruling && !chroma && !figure && !dark) {
            kept=0.f; mask=BACKGROUND|BLEED;
        }
        rendered[i]=byte(255.f-kept);
        labels[i]=static_cast<jbyte>(mask);
        bleed[i]=byte(suspected ? (1-retention)*255.f : 0.f);
    }
    jclass byteArrayClass=env->FindClass("[B");
    auto arrays=env->NewObjectArray(3,byteArrayClass,nullptr);
    if (!arrays) return nullptr;
    int index=0;
    for (auto* values : {&rendered,&labels,&bleed}) {
        auto a=output(env,*values);if (!a) return nullptr;
        env->SetObjectArrayElement(arrays,index++,a);env->DeleteLocalRef(a);
    }
    env->DeleteLocalRef(byteArrayClass);
    return arrays;
}

extern "C" JNIEXPORT void JNICALL
Java_cn_paperforge_app_imaging_PaperPixelEngine_workbookPolish(JNIEnv* env, jobject,
    jbyteArray gray,jbyteArray labels,jint w,jint h,jint cap) {
    const int n=env->GetArrayLength(gray);
    if (w<1 || h<1 || static_cast<int64_t>(w)*h!=n || env->GetArrayLength(labels)!=n) {invalid(env);return;}
    Bytes source(env,gray), flags(env,labels);
    if (!source.data || !flags.data) return;
    std::vector<jbyte> result(source.data,source.data+n);
    for (int y=1;y<h-1;++y) for (int x=1;x<w-1;++x) {
        const int i=y*w+x,v=source[i];
        if (!(flags[i]&1) || (flags[i]&(2|4|8|16|128))) continue;
        float sum=v*4.f,weight=4.f;
        for (int dy=-1;dy<=1;++dy) for (int dx=-1;dx<=1;++dx) {
            if (!dx && !dy) continue;
            const int other=source[(y+dy)*w+x+dx];
            const float a=std::max(0.f,1.f-std::abs(other-v)/20.f);
            sum+=other*a;weight+=a;
        }
        const int limit=std::clamp(static_cast<int>(cap),0,5);
        int changed=std::clamp(static_cast<int>(std::floor(sum/weight+.5f)),v-limit,v+limit);
        // No pixel can cross any of 32,64,...,224. Components/holes at those thresholds are exact.
        changed=std::clamp(changed,(v/32)*32,std::min(255,(v/32)*32+31));
        result[i]=static_cast<jbyte>(changed);
    }
    env->SetByteArrayRegion(gray,0,n,result.data());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_paperforge_app_imaging_PaperPixelEngine_workbookColor(JNIEnv* env, jobject,
    jbyteArray rgb,jbyteArray paper,jbyteArray gray,jbyteArray labels,jfloat amount,jfloat threshold) {
    const int n=env->GetArrayLength(gray);
    if (env->GetArrayLength(rgb)!=n*3 || env->GetArrayLength(paper)!=n || env->GetArrayLength(labels)!=n) {invalid(env);return nullptr;}
    Bytes color(env,rgb), field(env,paper), tone(env,gray), flags(env,labels);
    if (!color.data || !field.data || !tone.data || !flags.data) return nullptr;
    std::vector<jbyte> result(n*3);
    const PaperTint tint=paperTint(color,n);
    for (int i=0;i<n;++i) {
        const int r=color[i*3],g=color[i*3+1],b=color[i*3+2];
        const bool chroma=(flags[i]&2) && tint.chroma(r,g,b)>=threshold;
        for (int k=0;k<3;++k) {
            float v=tone[i];
            if (chroma) {
                const float normalized=std::min(255.f,color[i*3+k]*255.f/std::max(20,static_cast<int>(field[i])));
                // Preserve colour from the source, never colourise model output.
                v=color[i*3+k]*(1-clamp(amount))+normalized*clamp(amount);
            }
            result[i*3+k]=byte(v);
        }
    }
    return output(env,result);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_paperforge_app_imaging_PaperPixelEngine_componentSeeds(JNIEnv* env, jobject, jbyteArray ink, jintArray labels, jintArray stats) {
    const int size=env->GetArrayLength(ink), count=env->GetArrayLength(stats)/5;
    if (env->GetArrayLength(labels)!=size || count<1 || env->GetArrayLength(stats)!=count*5) { invalid(env); return nullptr; }
    Bytes c(env,ink); Ints ids(env,labels), data(env,stats);
    if (!c.data || !ids.data || !data.data) return nullptr;
    std::vector<int> peak(count,0);
    for (int i=0;i<size;++i) peak[ids[i]]=std::max(peak[ids[i]],static_cast<int>(c[i]));
    std::vector<bool> reliable(count,false);
    for (int id=1;id<count;++id) reliable[id]=data[id*5+4]>=std::max(18,size/150000) && peak[id]>=50;
    std::vector<jbyte> result(size);
    for (int i=0;i<size;++i) result[i]=reliable[ids[i]] ? static_cast<jbyte>(255) : 0;
    return output(env,result);
}
extern "C" JNIEXPORT void JNICALL
Java_cn_paperforge_app_imaging_PaperPixelEngine_componentClean(JNIEnv* env, jobject, jbyteArray rendered, jbyteArray ink, jintArray labels, jintArray stats, jbyteArray nearby, jfloat amount, jint maximum) {
    const int size=env->GetArrayLength(rendered), count=env->GetArrayLength(stats)/5;
    if (env->GetArrayLength(ink)!=size || env->GetArrayLength(labels)!=size || env->GetArrayLength(nearby)!=size || count<1 || env->GetArrayLength(stats)!=count*5) { invalid(env); return; }
    Bytes original(env,rendered), c(env,ink), context(env,nearby); Ints ids(env,labels), data(env,stats);
    if (!original.data || !c.data || !context.data || !ids.data || !data.data) return;
    std::vector<int> peak(count,0), support(count,0);
    for (int i=0;i<size;++i) {
        const int id=ids[i]; peak[id]=std::max(peak[id],static_cast<int>(c[i]));
        if (context[i]) ++support[id];
    }
    std::vector<bool> drop(count,false);
    for (int id=1;id<count;++id) {
        const int w=data[id*5+2],h=data[id*5+3],area=data[id*5+4];
        const bool elongated=std::max(w,h)>=10 && std::max(w,h)>=4*std::min(w,h) && peak[id]>=70;
        drop[id]=area<=maximum && support[id]<area*.2 && !elongated;
    }
    std::vector<jbyte> result(size);
    for (int i=0;i<size;++i) result[i]=drop[ids[i]] ? byte(original[i]+(255-original[i])*amount) : original.data[i];
    env->SetByteArrayRegion(rendered,0,size,result.data());
}
extern "C" JNIEXPORT void JNICALL
Java_cn_paperforge_app_imaging_PaperPixelEngine_faintClean(JNIEnv* env, jobject, jbyteArray rendered, jbyteArray rules, jbyteArray foreground, jint width, jint height, jfloatArray params) {
    const int size=env->GetArrayLength(rendered);
    if (width<=0 || height<=0 || static_cast<int64_t>(width)*height!=size || env->GetArrayLength(rules)!=size || (foreground && env->GetArrayLength(foreground)!=size) || env->GetArrayLength(params)!=6) { invalid(env); return; }
    float p[6];env->GetFloatArrayRegion(params,0,6,p);
    Bytes original(env,rendered), line(env,rules), matte(env,foreground);
    if (!original.data || !line.data || (foreground && !matte.data)) return;
    const int radius=std::clamp(static_cast<int>(std::floor(p[3]+.5f)),1,4);
    std::vector<jbyte> result(original.data,original.data+size);
    for (int y=0;y<height;++y) for (int x=0;x<width;++x) {
        const int i=y*width+x,v=original[i];
        if (v<p[0] || v>=p[1] || line[i]>=6 || (foreground && matte[i]>=p[5]*255.f)) continue;
        bool hasCore=false;
        for (int yy=std::max(0,y-radius);yy<=std::min(height-1,y+radius) && !hasCore;++yy)
            for (int xx=std::max(0,x-radius);xx<=std::min(width-1,x+radius);++xx)
                if (original[yy*width+xx]<p[2]) {hasCore=true;break;}
        if (!hasCore) result[i]=byte(v+(255-v)*clamp(p[4]));
    }
    env->SetByteArrayRegion(rendered,0,size,result.data());
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_paperforge_app_imaging_PaperPixelEngine_normalize(JNIEnv* env, jobject, jbyteArray raw, jbyteArray paper, jint floor) {
    const int size = env->GetArrayLength(raw);
    if (env->GetArrayLength(paper) != size) { invalid(env); return nullptr; }
    Bytes a(env, raw), b(env, paper);
    if (!a.data || !b.data) return nullptr;
    std::vector<jbyte> result(size);
    for (int i=0; i<size; ++i) result[i] = byte(255.f - std::min(255.f, a[i] * 255.f / std::max(floor, static_cast<int>(b[i]))));
    return output(env, result);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_paperforge_app_imaging_PaperPixelEngine_workbook(JNIEnv* env, jobject, jbyteArray raw, jbyteArray ink, jbyteArray soft, jbyteArray near, jbyteArray rules, jbyteArray foreground, jfloatArray params) {
    const int size = env->GetArrayLength(raw);
    if (env->GetArrayLength(ink)!=size || env->GetArrayLength(soft)!=size || env->GetArrayLength(near)!=size || env->GetArrayLength(rules)!=size || (foreground && env->GetArrayLength(foreground)!=size) || env->GetArrayLength(params)!=20) { invalid(env); return nullptr; }
    // Parameter order mirrors PaperPixelEngine.workbook: noise, black, gamma, detail gain,
    // faint floor/high, faint retention, output floor, protection, seed low/high,
    // rule floor/range/gain, smooth evidence, blend, learned floor/range/strength, rule ink ceiling.
    float p[20]; env->GetFloatArrayRegion(params,0,20,p);
    Bytes original(env,raw), c(env,ink), low(env,soft), neighbours(env,near), line(env,rules), matte(env,foreground);
    if (env->ExceptionCheck() || !original.data || !c.data || !low.data || !neighbours.data || !line.data || (foreground && !matte.data)) return nullptr;
    float base[256], faint[256], retained[256], rule[256];
    for (int i=0; i<256; ++i) {
        base[i]=255.f*std::pow(clamp((i-p[0])/(p[1]-p[0])),p[2]);
        float t=clamp((i-p[4])/(p[5]-p[4]));
        faint[i]=p[14]>0 ? t*t*(3.f-2.f*t) : t;
        retained[i]=std::max(base[i],std::min(255.f,i*p[3]));
        rule[i]=clamp((i-p[11])/p[12]);
    }
    std::vector<jbyte> result(size);
    for (int i=0; i<size; ++i) {
        const int value=c[i];
        const float acuity=clamp((value-low[i]-3.f)/(value*.3f+1.f));
        const float connected=clamp((neighbours[i]-p[9])/(p[10]-p[9]));
        const float weak=faint[value]+(1.f-faint[value])*p[6];
        const float preserve=std::max(acuity*weak,connected)*p[8];
        const float ruled=std::min(210.f,std::min(static_cast<float>(value),line[i]*p[19])*p[13])*rule[line[i]];
        float out=base[value]+(retained[value]-base[value])*preserve;
        if (foreground) {
            float t=clamp((matte[i]/255.f-p[16])/std::max(.01f,p[17]));
            float confidence=t*t*(3.f-2.f*t);
            const float keep=std::max(clamp((value-90.f)/65.f),clamp((neighbours[i]-95.f)/50.f));
            out *= 1.f-p[18]+p[18]*std::max(confidence,keep);
        }
        out=std::max(out,ruled);
        const float enhanced=255.f-std::max(0.f,out-p[7])*255.f/(255.f-p[7]);
        result[i]=byte(original[i]+(enhanced-original[i])*p[15]);
    }
    return output(env,result);
}
