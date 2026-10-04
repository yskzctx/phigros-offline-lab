#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdatomic.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <time.h>
#ifndef OFFLINE_HOST_TEST
#include <sys/mman.h>
#include <link.h>
#include <jni.h>
#include <android/log.h>
#include "expected-hooks.h"
#else
#define ANDROID_LOG_INFO 4
static int __android_log_print(int priority,const char *tag,const char *format,...){(void)priority;(void)tag;(void)format;return 0;}
#endif
#include "controls-plan.h"

static atomic_bool enabled = false;
static atomic_bool ready = false;
static atomic_bool session_dirty = false;
static pthread_mutex_t config_lock=PTHREAD_MUTEX_INITIALIZER;
static ControlConfig pending_config={.target=1000000,.accuracy=10000};
static ControlConfig chart_config;
static atomic_int status_total,status_planned,status_hit;
static atomic_int status_score,status_accuracy,status_miss,status_good,status_bad;
static atomic_uint config_revision=1;
static void *plan_score,*plan_list;
static int plan_size,plan_last_count=-1;
static unsigned char selected[MAX_NOTES+1],consumed[MAX_NOTES+1];
static uint32_t chart_seed=0x179af31bu;
static uintptr_t image;
typedef bool (*judge_fn)(void *,const void *);
typedef void (*record_fn)(void *,void *,void *,void *,const void *);
static judge_fn click_original, drag_original, flick_original, hold_original;
static record_fn record_original;
typedef struct {float x,y,z;} Vec3;
typedef void (*perfect_fn)(void *,float,float,Vec3,bool,const void *);
typedef void *(*result_fn)(void *,const void *);
static perfect_fn perfect_original;
static result_fn result_original;
static perfect_fn good_call;
static void (*miss_original)(void *,float,const void *);
static void (*bad_call)(void *,float,float,const void *);

static inline void *pointer(void *object,size_t offset) {
    return *(void **)((char *)object+offset);
}
static inline uint8_t *byte(void *object,size_t offset) { return (uint8_t *)object+offset; }
static inline float *number(void *object,size_t offset) { return (float *)((char *)object+offset); }
static inline int *integer(void *object,size_t offset) { return (int *)((char *)object+offset); }

static int judged_count(void *score) {
    return *integer(score,0x58)+*integer(score,0x5c)+*integer(score,0x60)+*integer(score,0x64);
}
static int ordinal(void *score,float code) {
    void *level=pointer(score,0x30);
    void *list=level?pointer(level,0x38):NULL;
    if(!list)return 0;
    // List<object>.get_Item at RVA 0x2e4f3bc verifies these ARM64 layouts.
    int n=*integer(list,0x18);
    void *array=pointer(list,0x10);
    if(!array || n<1 || n>MAX_NOTES || *(size_t *)((char *)array+0x18)<(size_t)n)return 0;
    int count=judged_count(score);
    if(plan_score!=score || plan_list!=list || plan_size!=n || count<plan_last_count) {
        pthread_mutex_lock(&config_lock);chart_config=pending_config;pthread_mutex_unlock(&config_lock);
        plan_score=score;plan_list=list;plan_size=n;
        memset(consumed,0,(size_t)n+1);
        PlanEstimate predicted;
        int planned=make_profile(&chart_config,n,plan_random(&chart_seed),selected,&predicted);
        atomic_store(&status_total,n);atomic_store(&status_planned,planned);atomic_store(&status_hit,0);
        atomic_store(&status_score,predicted.score);atomic_store(&status_accuracy,predicted.accuracy);
        atomic_store(&status_miss,predicted.miss);atomic_store(&status_good,predicted.good);atomic_store(&status_bad,predicted.bad);
        __android_log_print(ANDROID_LOG_INFO,"PhigrosOffline","control.chart notes=%d planned=%d mode=%d target=%d estimate=%d acc=%d M=%d G=%d B=%d rules=%d already_judged=%d",n,planned,chart_config.mode,chart_config.target,predicted.score,predicted.accuracy,predicted.miss,predicted.good,predicted.bad,chart_config.rule_count,count);
    }
    plan_last_count=count;
    for(int i=0;i<n;i++) {
        void *note=pointer(array,0x20+(size_t)i*sizeof(void *));
        if(note && *number(note,0x38)==code)return i+1;
    }
    return 0;
}
static void mark_hit(int i,int judgment) {
    if(i && selected[i]==judgment && !consumed[i]) {
        consumed[i]=1;atomic_fetch_add(&status_hit,1);
        __android_log_print(ANDROID_LOG_INFO,"PhigrosOffline","control.note ordinal=%d judgment=%d",i,judgment);
    }
}
static void miss(void *score,float code,const void *method) {
    if(atomic_load(&ready)&&atomic_load(&enabled)&&score)mark_hit(ordinal(score,code),1);
    miss_original(score,code,method);
}
static void perfect(void *score,float code,float time,Vec3 position,bool is_hold,const void *method) {
    if(!atomic_load(&ready) || !atomic_load(&enabled) || !score) {
        perfect_original(score,code,time,position,is_hold,method);return;
    }
    int i=ordinal(score,code);
    if(i && selected[i] && !consumed[i]) {
        int judgment=selected[i];mark_hit(i,judgment);
        if(judgment==1) {
            miss_original(score,code,NULL);
        } else if(judgment==2) {
            good_call(score,code,0.08f,position,is_hold,NULL);
        } else {
            bad_call(score,code,0.15f,NULL);
        }
        return;
    }
    perfect_original(score,code,time,position,is_hold,method);
}
static void *result(void *score,const void *method) {
    void *out=result_original(score,method);
    if(out && score==plan_score && atomic_load(&enabled) && atomic_load(&ready)) {
        __android_log_print(ANDROID_LOG_INFO,"PhigrosOffline","control.result score=%d percent=%.4f P=%d G=%d B=%d M=%d planned=%d executed=%d",*integer(out,0x10),*number(out,0x18),*integer(out,0x1c),*integer(out,0x20),*integer(out,0x24),*integer(out,0x28),atomic_load(&status_planned),atomic_load(&status_hit));
        plan_score=NULL;
    }
    return out;
}

static bool automatic(void *self, const void *method, judge_fn original, int type) {
    if (!atomic_load(&ready) || !atomic_load(&enabled) || !self) return original(self,method);
    void *note=pointer(self,type==0?0x40:0x38);
    void *progress=pointer(self,0x28);
    if (!note || !progress) return original(self,method);
    void *judge=pointer(progress,0x30);
    // Paused / blocked story transitions must keep their original logic.
    if ((judge && *byte(judge,0x88)) || !*byte(progress,0x8a)) return false;
    float time=*number(progress,0x90), due=*number(note,0x2c);
    if (time < due) return false;
    void *score=pointer(self,0x20);
    int i=score?ordinal(score,*number(note,0x38)):0;
    // A planned Miss must visibly follow the game's unhit-note timeout path.
    // Do not set synthetic input, isJudged, flick or hold grace flags for it.
    if(i && selected[i]==1)return original(self,method);
    bool starting=type!=3 || !*byte(self,0x8e);
    if (type==0 || type==3) *byte(note,0x28)=1;
    if (type==1) *byte(self,0x60)=1;
    if (type==2) { *byte(note,0x29)=1; *byte(self,0x60)=1; }
    if (type==3) {
        // The original hold routine permits a two-frame grace period.
        // Refresh it while the synthetic finger remains on the hold.
        *(int *)((char *)self+0xb4)=2;
        *byte(self,0x8d)=0;
    }
    if (starting) *number(progress,0x90)=due;
    bool result=original(self,method);
    if (starting) *number(progress,0x90)=time;
    return result;
}
static bool click(void *s,const void *m) { return automatic(s,m,click_original,0); }
static bool drag(void *s,const void *m) { return automatic(s,m,drag_original,1); }
static bool flick(void *s,const void *m) { return automatic(s,m,flick_original,2); }
static bool hold(void *s,const void *m) { return automatic(s,m,hold_original,3); }
static void record(void *s,void *song,void *difficulty,void *result,const void *m) {
    if (!atomic_load(&session_dirty) || !atomic_load(&ready)) record_original(s,song,difficulty,result,m);
}

#ifndef OFFLINE_HOST_TEST
static void emit_absolute(uint32_t **cursor,unsigned reg,uintptr_t address,bool branch) {
    *(*cursor)++ = 0x58000040u | reg; // ldr Xreg, PC+8
    *(*cursor)++ = branch ? (0xd61f0000u | reg<<5) : 0x14000003u;
    memcpy(*cursor,&address,8); *cursor+=2;
}
static int install(const struct HookSpec *spec,void *replacement,void **original) {
    unsigned char *target=(unsigned char *)(image+spec->rva);
    if (memcmp(target,spec->prefix,16)!=0) return -1;
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    uint32_t *trampoline=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    if (trampoline==MAP_FAILED) return -2;
    uint32_t *out=trampoline;
    for (int i=0;i<4;i++) {
        uint32_t instruction;memcpy(&instruction,target+i*4,4);
        if ((instruction & 0x9f000000u)==0x90000000u) {
            // Relocate ADRP to the same absolute original-image page.
            int64_t immediate=((instruction>>29)&3u) | (((instruction>>5)&0x7ffffu)<<2);
            if (immediate & (1<<20)) immediate-=1<<21;
            uintptr_t value=((uintptr_t)(target+i*4)&~(uintptr_t)4095)+(immediate*4096);
            emit_absolute(&out,instruction&31u,value,false);
        } else {
            // Only the audited non-PC-relative prologue instructions are accepted.
            if (spec->relocate_mask & (1u<<i)) { munmap(trampoline,page);return -3; }
            *out++=instruction;
        }
    }
    emit_absolute(&out,17,(uintptr_t)(target+16),true);
    __builtin___clear_cache((char *)trampoline,(char *)out);
    if (mprotect(trampoline,page,PROT_READ|PROT_EXEC)!=0) { munmap(trampoline,page);return -4; }
    *original=trampoline;
    uintptr_t first=(uintptr_t)target&~(page-1);
    size_t length=((uintptr_t)target+16-first+page-1)&~(page-1);
    if (mprotect((void *)first,length,PROT_READ|PROT_WRITE|PROT_EXEC)!=0) return -5;
    uint32_t jump[4],*cursor=jump;emit_absolute(&cursor,17,(uintptr_t)replacement,true);
    memcpy(target,jump,16);
    __builtin___clear_cache((char *)target,(char *)target+16);
    mprotect((void *)first,length,PROT_READ|PROT_EXEC);
    return 0;
}
static int find_image(struct dl_phdr_info *info,size_t size,void *data) {
    (void)size;(void)data;
    const char *name=strrchr(info->dlpi_name,'/');name=name?name+1:info->dlpi_name;
    if (strcmp(name,"libil2cpp.so")==0) { image=info->dlpi_addr;return 1; }
    return 0;
}
static void *worker(void *unused) {
    (void)unused;
    for (int i=0;i<1800 && !image;i++) { dl_iterate_phdr(find_image,NULL);if (!image) usleep(100000); }
    if (!image) return NULL;
    // Validate every hook before changing any code; offset data applies only to 4.0.1 (157).
    for (size_t i=0;i<sizeof(hooks)/sizeof(hooks[0]);i++) if (memcmp((void *)(image+hooks[i].rva),hooks[i].prefix,16)) {
        __android_log_print(ANDROID_LOG_ERROR,"PhigrosOffline","Client version mismatch; autoplay disabled");
        atomic_store(&enabled,false);return NULL;
    }
    good_call=(perfect_fn)(image+0x1d876b8);
    bad_call=(void (*)(void *,float,float,const void *))(image+0x1d875c4);
    void *replacements[]={(void *)click,(void *)drag,(void *)flick,(void *)hold,(void *)record,(void *)perfect,(void *)result,(void *)miss};
    void **originals[]={(void **)&click_original,(void **)&drag_original,(void **)&flick_original,(void **)&hold_original,(void **)&record_original,(void **)&perfect_original,(void **)&result_original,(void **)&miss_original};
    for (size_t i=0;i<sizeof(replacements)/sizeof(replacements[0]);i++) if (install(&hooks[i],replacements[i],originals[i])) {
        atomic_store(&enabled,false);
        __android_log_print(ANDROID_LOG_ERROR,"PhigrosOffline","Autoplay hook installation failed: %zu",i);
        return NULL;
    }
    atomic_store(&ready,true);
    __android_log_print(ANDROID_LOG_INFO,"PhigrosOffline","Autoplay hooks ready");
    return NULL;
}
JNIEXPORT void JNICALL Java_com_phigros_offline_LaunchActivity_setAutoplay(JNIEnv *env,jclass clazz,jboolean value) {
    (void)env;(void)clazz;
    if(value==JNI_TRUE)atomic_store(&session_dirty,true);
    atomic_store(&enabled,value==JNI_TRUE);
}
JNIEXPORT jboolean JNICALL Java_com_phigros_offline_NativeControls_configure(JNIEnv *env,jclass cls,jint mode,jint target,jint accuracy,jintArray words) {
    (void)cls;
    if(mode<0||mode>2||target<0||target>1000000||accuracy<0||accuracy>10000||!words)return JNI_FALSE;
    int length=(*env)->GetArrayLength(env,words);
    if(length<1||length>MAX_CONFIG_WORDS)return JNI_FALSE;
    jint packed[MAX_CONFIG_WORDS];(*env)->GetIntArrayRegion(env,words,0,length,packed);
    if((*env)->ExceptionCheck(env))return JNI_FALSE;
    ControlConfig c={.mode=mode,.target=target,.accuracy=accuracy,.rule_count=packed[0]};
    if(c.rule_count<0||c.rule_count>MAX_RULES)return JNI_FALSE;
    int cursor=1;
    for(int i=0;i<c.rule_count;i++){
        if(cursor+6>length)return JNI_FALSE;
        NoteRule *r=&c.rules[i];r->judgment=packed[cursor++];r->position=packed[cursor++];r->start=packed[cursor++];r->end=packed[cursor++];r->count=packed[cursor++];r->length=packed[cursor++];
        if(r->judgment<1||r->judgment>3||r->position<1||r->position>6||r->start<1||r->start>MAX_NOTES||r->end<1||r->end>MAX_NOTES||r->count<1||r->count>MAX_NOTES||r->length<0||r->length>MAX_INDICES||cursor+r->length>length||(r->position==2&&r->end<r->start)||(r->position==3&&r->length==0))return JNI_FALSE;
        for(int k=0;k<r->length;k++){int v=packed[cursor++];if(v<1||v>MAX_NOTES)return JNI_FALSE;r->indices[k]=v;}
    }
    if(cursor!=length)return JNI_FALSE;
    pthread_mutex_lock(&config_lock);pending_config=c;pthread_mutex_unlock(&config_lock);
    atomic_fetch_add(&config_revision,1);return JNI_TRUE;
}
JNIEXPORT jintArray JNICALL Java_com_phigros_offline_NativeControls_status(JNIEnv *env,jclass cls) {
    (void)cls;
    jint values[]={atomic_load(&ready),atomic_load(&enabled),atomic_load(&status_total),atomic_load(&status_planned),atomic_load(&status_hit),(jint)atomic_load(&config_revision),atomic_load(&status_score),atomic_load(&status_accuracy),atomic_load(&status_miss),atomic_load(&status_good),atomic_load(&status_bad)};
    jintArray out=(*env)->NewIntArray(env,11);
    if(out)(*env)->SetIntArrayRegion(env,out,0,11,values);return out;
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm,void *reserved) {
    (void)vm;(void)reserved;
    struct timespec now;if(clock_gettime(CLOCK_MONOTONIC,&now)==0)chart_seed^=(uint32_t)now.tv_nsec^(uint32_t)now.tv_sec;
    pthread_t thread;
    if (!pthread_create(&thread,NULL,worker,NULL)) pthread_detach(thread);
    return JNI_VERSION_1_6;
}
#endif
