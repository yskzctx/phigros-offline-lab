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
static atomic_int status_final,status_actual_score,status_actual_accuracy;
static atomic_int status_actual_perfect,status_actual_good,status_actual_bad,status_actual_miss,status_actual_combo;
static atomic_int status_actual_early,status_actual_late;
static atomic_uint config_revision=1;
static void *plan_score,*plan_list,*plan_array;
static int plan_size,plan_last_count=-1;
static unsigned char selected[MAX_NOTES+1],consumed[MAX_NOTES+1];
static unsigned char note_types[MAX_NOTES+1];
static PlanTiming note_timing[MAX_NOTES+1];
static uint32_t chart_seed=0x179af31bu;
static uint32_t chart_plan_seed;
static uintptr_t image;
#define ORDINAL_SLOTS 65536u
static uint32_t ordinal_keys[ORDINAL_SLOTS];
static int ordinal_values[ORDINAL_SLOTS];
static uint32_t ordinal_key(float code){uint32_t key;if(code==0.f)code=0.f;memcpy(&key,&code,4);return key;}
static uint32_t ordinal_slot(uint32_t key){key^=key>>16;key*=0x7feb352du;key^=key>>15;key*=0x846ca68bu;key^=key>>16;return key&(ORDINAL_SLOTS-1);}
static void cache_ordinal(float code,int position){
    if(isnan(code))return;
    uint32_t key=ordinal_key(code),slot=ordinal_slot(key);
    while(ordinal_values[slot]){if(ordinal_keys[slot]==key)return;slot=(slot+1)&(ORDINAL_SLOTS-1);}
    ordinal_keys[slot]=key;ordinal_values[slot]=position;
}
static int cached_ordinal(float code){
    if(isnan(code))return 0;
    uint32_t key=ordinal_key(code),slot=ordinal_slot(key);
    while(ordinal_values[slot]){if(ordinal_keys[slot]==key)return ordinal_values[slot];slot=(slot+1)&(ORDINAL_SLOTS-1);}
    return 0;
}
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
// Only active while one non-Tap original Judge executes its unhit cleanup.
static _Thread_local void *early_bad_score;
static _Thread_local float early_bad_code,early_bad_delay;

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
    if(plan_score!=score || plan_list!=list || plan_array!=array || plan_size!=n || count<plan_last_count) {
        pthread_mutex_lock(&config_lock);chart_config=pending_config;pthread_mutex_unlock(&config_lock);
        plan_score=score;plan_list=list;plan_array=array;plan_size=n;
        memset(ordinal_values,0,sizeof(ordinal_values));
        memset(consumed,0,(size_t)n+1);
        PlanEstimate predicted;
        for(int k=0;k<n;k++){
            void *note=pointer(array,0x20+(size_t)k*sizeof(void *));
            if(note)cache_ordinal(*number(note,0x38),k+1);
            int type=note?*integer(note,0x10):0;
            note_types[k+1]=(unsigned char)(type>=1&&type<=4?type:0);
            float start=note?*number(note,0x2c):0.f;
            note_timing[k+1]=(PlanTiming){start,start+(type==3?*number(note,0x1c):0.f)};
        }
        float good_delay=0.13f,miss_delay=0.18f;
        void *progress=pointer(score,0x90),*judge=progress?pointer(progress,0x30):NULL;
        void *klass=judge?pointer(judge,0):NULL,*fields=klass?pointer(klass,0xb8):NULL;
        if(fields){float p=*number(fields,4),g=*number(fields,8);if(p>0.f&&g>p&&g<1.f){good_delay=(p+g)*0.5f;miss_delay=g;}}
        chart_plan_seed=plan_random(&chart_seed);if(!chart_plan_seed)chart_plan_seed=1;
        float bad_limit=fields?*number(fields,12):.22f;
        if(!(bad_limit>miss_delay&&bad_limit<1.f))bad_limit=.22f;
        PlanTimeline timeline=plan_timeline_windows(note_timing,note_types,n,good_delay,miss_delay,(miss_delay+bad_limit)*.5f,chart_plan_seed);
        timeline.challenge=fields&&*byte(fields,0);
        timeline.denominator=*integer(level,0x2c)>0?*integer(level,0x2c):n;
        int planned=make_profile_timed(&chart_config,n,chart_plan_seed,selected,&predicted,note_types,&timeline);
        free(timeline.events);atomic_store(&status_final,0);
        atomic_store(&status_total,n);atomic_store(&status_planned,planned);atomic_store(&status_hit,0);
        atomic_store(&status_score,predicted.score);atomic_store(&status_accuracy,predicted.accuracy);
        atomic_store(&status_miss,predicted.miss);atomic_store(&status_good,predicted.good);atomic_store(&status_bad,predicted.bad);
        __android_log_print(ANDROID_LOG_INFO,"PhigrosOffline","control.chart notes=%d planned=%d mode=%d target=%d estimate=%d acc=%d M=%d G=%d B=%d rules=%d already_judged=%d",n,planned,chart_config.mode,chart_config.target,predicted.score,predicted.accuracy,predicted.miss,predicted.good,predicted.bad,chart_config.rule_count,count);
    }
    plan_last_count=count;
    return cached_ordinal(code);
}
static void mark_hit(int i,int judgment) {
    if(i && selected[i]==judgment && !consumed[i]) {
        consumed[i]=1;atomic_fetch_add(&status_hit,1);
        __android_log_print(ANDROID_LOG_INFO,"PhigrosOffline","control.note ordinal=%d judgment=%d",i,judgment);
    }
}
static void miss(void *score,float code,const void *method) {
    if(score==early_bad_score&&code==early_bad_code){
        int i=ordinal(score,code);
        if(i&&selected[i]==3&&!consumed[i]){mark_hit(i,3);bad_call(score,code,early_bad_delay,method);return;}
    }
    if(atomic_load(&ready)&&atomic_load(&enabled)&&score)mark_hit(ordinal(score,code),1);
    miss_original(score,code,method);
}
static void good(void *score,float code,float time,Vec3 position,bool is_hold,const void *method) {
    if(atomic_load(&ready)&&atomic_load(&enabled)&&score){int i=ordinal(score,code);if(i&&plan_is_good(selected[i]))mark_hit(i,selected[i]);}
    good_call(score,code,time,position,is_hold,method);
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
        } else if(plan_is_good(judgment)) {
            good_call(score,code,plan_good_early(judgment,i,chart_plan_seed)?-0.13f:0.13f,position,is_hold,NULL);
        } else {
            bad_call(score,code,0.15f,NULL);
        }
        return;
    }
    perfect_original(score,code,time,position,is_hold,method);
}
// ---- forced result display -------------------------------------------------
// An independent opt-in switch: when on, the settlement screen shows the values
// the operator configured instead of the run's real outcome. It is deliberately
// decoupled from autoplay and from judgment protection, and it never changes the
// judgments that were actually executed - only the numbers the result object
// carries into the settlement screen and the local record.
static atomic_bool force_enabled=false;
static atomic_int force_score,force_accuracy,force_perfect,force_good,force_bad,force_miss,force_combo;
// Good 的早/晚计数。结算页会分别显示，所以强制显示也应能单独设置，而不是固定为 0。
static atomic_int force_early,force_late;
static atomic_bool force_early_late_on;
// 结算符号（Φ / FC）。符号不是独立字段，而是游戏用判定数量派生出来的：全 Perfect
// 判为 Φ，没有 Bad/Miss 判为 FC。所以强制符号只有一条可靠办法——把判定数量改成
// 能推出该符号的组合。0=不干预(按填写的判定数), 1=Φ, 2=FC。
static atomic_int force_symbol;

static bool force_result_apply(void *out) {
    if (!out || !atomic_load(&force_enabled)) return false;
    // Values are written as configured, including combinations a real run could
    // never produce. Only NaN accuracy is refused, because it would corrupt the
    // float field the settlement screen formats.
    *integer(out,0x10)=atomic_load(&force_score);
    int accuracy=atomic_load(&force_accuracy);
    *number(out,0x18)=(float)accuracy/100.f;
    *integer(out,0x1c)=atomic_load(&force_perfect);
    *integer(out,0x20)=atomic_load(&force_good);
    *integer(out,0x24)=atomic_load(&force_bad);
    *integer(out,0x28)=atomic_load(&force_miss);
    // 结算符号由判定数量派生，因此按用户选择的符号改判定数，是最可靠的写法：
    //   Φ  = 全部 Perfect（Good/Bad/Miss 归零）
    //   FC = 无 Bad、无 Miss
    // 0 表示不干预，完全按上面填写的数量显示。
    int symbol=atomic_load(&force_symbol);
    if(symbol==1){
        int total=*integer(out,0x1c)+*integer(out,0x20)+*integer(out,0x24)+*integer(out,0x28);
        *integer(out,0x1c)=total;*integer(out,0x20)=0;*integer(out,0x24)=0;*integer(out,0x28)=0;
    } else if(symbol==2){
        *integer(out,0x24)=0;*integer(out,0x28)=0;
    }
    *integer(out,0x34)=atomic_load(&force_combo);
    // Early/Late are shown separately on the settlement screen, so they must be
    // settable too. Written only when the user opted in; otherwise zeroed as before, so
    // a configuration that never touches them behaves exactly as it used to.
    if(atomic_load(&force_early_late_on)){
        *integer(out,0x2c)=atomic_load(&force_early);
        *integer(out,0x30)=atomic_load(&force_late);
    } else {
        *integer(out,0x2c)=0;*integer(out,0x30)=0;
    }
    return true;
}

static void *result(void *score,const void *method) {
    void *out=result_original(score,method);
    if(out && force_result_apply(out)) {
        __android_log_print(ANDROID_LOG_INFO,"PhigrosOffline","control.result.forced score=%d acc=%d P=%d G=%d B=%d M=%d combo=%d",
            *integer(out,0x10),(int)(*number(out,0x18)*100.f+0.5f),*integer(out,0x1c),*integer(out,0x20),
            *integer(out,0x24),*integer(out,0x28),*integer(out,0x34));
    }
    if(out && score==plan_score && atomic_load(&enabled) && atomic_load(&ready)) {
        // Read the game's real result. Never replace its score or judgments.
        atomic_store(&status_actual_score,*integer(out,0x10));
        atomic_store(&status_actual_accuracy,(int)(*number(out,0x18)*100.f+0.5f));
        atomic_store(&status_actual_perfect,*integer(out,0x1c));atomic_store(&status_actual_good,*integer(out,0x20));
        atomic_store(&status_actual_bad,*integer(out,0x24));atomic_store(&status_actual_miss,*integer(out,0x28));
        atomic_store(&status_actual_combo,*integer(out,0x34));
        atomic_store(&status_actual_early,*integer(out,0x2c));atomic_store(&status_actual_late,*integer(out,0x30));
        atomic_store(&status_final,1);
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
    void *score=pointer(self,0x20);
    int i=score?ordinal(score,*number(note,0x38)):0;
    // With blocking off, preserve a real touch that already selected this note.
    // Synthetic input is only used when the original control did not consume it.
    if(!chart_config.block_touch&&(*byte(note,0x28)||*byte(note,0x29)||*byte(self,0x60))){
        bool real=original(self,method);if(real)return true;
    }
    // A planned Miss must visibly follow the game's unhit-note timeout path.
    // Do not set synthetic input, isJudged, flick or hold grace flags for it.
    if(i && selected[i]==1)return time<due?false:original(self,method);
    bool starting=type!=3 || !*byte(self,0x8e);
    if(starting&&i&&selected[i]==3){
        void *klass=judge?pointer(judge,0):NULL;
        void *fields=klass?pointer(klass,0xb8):NULL;
        float g=fields?*number(fields,8):.18f,b=fields?*number(fields,12):.22f;
        if(!(g>0.f&&b>g&&b<1.f)){g=.18f;b=.22f;}
        float delay=-(g+b)*.5f;
        if(time<due+delay)return false;
        // ClickControl.Judge 0x1d8242c takes the real early-Bad branch:
        // no normal hit sound; its original Bad object stays at note height.
        if(type==0){
            *byte(note,0x28)=1;*number(progress,0x90)=due+delay;
            bool done=original(self,method);*number(progress,0x90)=time;
            if(done)mark_hit(i,3);
            return done;
        }
        // Drag/Flick/Hold have no native early-Bad branch. Preserve the user's
        // opt-in forced Bad, using each original unhit-note cleanup (no hit
        // sound/Perfect effect), and substitute only its scoped score callback.
        early_bad_score=score;early_bad_code=*number(note,0x38);early_bad_delay=delay;
        float cleanup_after=2.f;
        if(type==3){float length=*number(note,0x1c);if(isfinite(length)&&length>0.f)cleanup_after=fmaxf(cleanup_after,length+.5f);}
        // HoldControl only returns its object after head + holdTime + .25s.
        *number(progress,0x90)=due+cleanup_after;
        bool done=original(self,method);
        *number(progress,0x90)=time;early_bad_score=NULL;return done;
    }
    // Tap/Hold can naturally enter Good. Delay their initial synthetic contact;
    // keep Drag/Flick's original timing and forced rule semantics unchanged.
    float head_time=due;
    if(starting&&i&&plan_is_good(selected[i])&&(type==0||type==3)){
        float delay=0.13f;
        // HoldControl.Judge (157) reads JudgeControl static_fields at class+0xb8,
        // PerfectTimeRange+4 and GoodTimeRange+8. The midpoint avoids float
        // boundary rounding and respects the tighter challenge-mode windows.
        void *klass=judge?pointer(judge,0):NULL;
        void *fields=klass?pointer(klass,0xb8):NULL;
        if(fields){float p=*number(fields,4),g=*number(fields,8);if(p>0.f&&g>p&&g<1.f)delay=(p+g)*0.5f;}
        head_time+=plan_good_early(selected[i],i,chart_plan_seed)?-delay:delay;
    }
    if(starting&&time<head_time)return false;
    if (type==0 || type==3) *byte(note,0x28)=1;
    if (type==1) *byte(self,0x60)=1;
    if (type==2) { *byte(note,0x29)=1; *byte(self,0x60)=1; }
    if (type==3) {
        // The original hold routine permits a two-frame grace period.
        // Refresh it while the synthetic finger remains on the hold.
        *(int *)((char *)self+0xb4)=2;
        *byte(self,0x8d)=0;
    }
    if (starting) *number(progress,0x90)=head_time;
    bool result=original(self,method);
    if (starting) *number(progress,0x90)=time;
    return result;
}
static bool click(void *s,const void *m) { return automatic(s,m,click_original,0); }
static bool drag(void *s,const void *m) { return automatic(s,m,drag_original,1); }
static bool flick(void *s,const void *m) { return automatic(s,m,flick_original,2); }
static bool hold(void *s,const void *m) { return automatic(s,m,hold_original,3); }
// The game calls this to persist a finished run. Swallowing it made automated
// sessions produce no record at all, so nothing could ever be uploaded even when
// the host's own upload path was intact. Writes now always go through; session
// bookkeeping (session_dirty) only decides whether the caller restores original
// records on the next cold start, which RksEditor already handles separately.
static void record(void *s,void *song,void *difficulty,void *result,const void *m) {
    record_original(s,song,difficulty,result,m);
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
    bad_call=(void (*)(void *,float,float,const void *))(image+0x1d875c4);
    void *replacements[]={(void *)click,(void *)drag,(void *)flick,(void *)hold,(void *)record,(void *)perfect,(void *)result,(void *)miss,(void *)good};
    void **originals[]={(void **)&click_original,(void **)&drag_original,(void **)&flick_original,(void **)&hold_original,(void **)&record_original,(void **)&perfect_original,(void **)&result_original,(void **)&miss_original,(void **)&good_call};
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
JNIEXPORT jboolean JNICALL Java_com_phigros_offline_NativeControls_configure(JNIEnv *env,jclass cls,jint mode,jint target,jint accuracy,jboolean block_touch,jint assist_guard,jint guard_score,jintArray words) {
    (void)cls;
    // mode 3 is judgment protection: same planning pipeline, different takeover
    // timing. It must be accepted here or the Java layer cannot save it at all.
    if(mode<0||mode>3||target<0||target>1000000||accuracy<0||accuracy>10000||assist_guard<0||assist_guard>3||guard_score<0||guard_score>1000000||!words)return JNI_FALSE;
    // Protection is only meaningful with a chosen protection kind. Requiring it
    // here keeps a stale goal_mode=3 from silently running as plain autoplay.
    if(mode==3&&(assist_guard<1||(assist_guard==3&&guard_score<1)))return JNI_FALSE;
    int length=(*env)->GetArrayLength(env,words);
    if(length<1||length>MAX_CONFIG_WORDS)return JNI_FALSE;
    jint packed[MAX_CONFIG_WORDS];(*env)->GetIntArrayRegion(env,words,0,length,packed);
    if((*env)->ExceptionCheck(env))return JNI_FALSE;
    bool typed=packed[0]==-168;
    if(typed&&length<2)return JNI_FALSE;
    ControlConfig c={.mode=mode,.target=target,.accuracy=accuracy,.block_touch=block_touch==JNI_TRUE,.assist_guard=assist_guard,.guard_score=guard_score,.rule_count=packed[typed?1:0]};
    if(c.rule_count<0||c.rule_count>MAX_RULES)return JNI_FALSE;
    // Protection keeps real touches authoritative while the score is still inside
    // the target, so blocking would defeat its purpose. Force it off regardless of
    // what the caller sent.
    if(mode==3)c.block_touch=false;
    int cursor=typed?2:1;
    for(int i=0;i<c.rule_count;i++){
        if(cursor+(typed?7:6)>length)return JNI_FALSE;
        NoteRule *r=&c.rules[i];r->judgment=packed[cursor++];r->position=packed[cursor++];r->start=packed[cursor++];r->end=packed[cursor++];r->count=packed[cursor++];if(typed)r->note_type=packed[cursor++];r->length=packed[cursor++];
        if(r->note_type<0||r->note_type>4)return JNI_FALSE;
        if(r->judgment<0||r->judgment>5||r->position<1||r->position>6||r->start<1||r->start>MAX_NOTES||r->end<1||r->end>MAX_NOTES||r->count<1||r->count>MAX_NOTES||r->length<0||r->length>MAX_INDICES||cursor+r->length>length||(r->position==2&&r->end<r->start)||(r->position==3&&r->length==0))return JNI_FALSE;
        for(int k=0;k<r->length;k++){int v=packed[cursor++];if(v<1||v>MAX_NOTES)return JNI_FALSE;r->indices[k]=v;}
    }
    if(cursor!=length)return JNI_FALSE;
    pthread_mutex_lock(&config_lock);pending_config=c;pthread_mutex_unlock(&config_lock);
    atomic_fetch_add(&config_revision,1);return JNI_TRUE;
}
JNIEXPORT jintArray JNICALL Java_com_phigros_offline_NativeControls_status(JNIEnv *env,jclass cls) {
    (void)cls;
    jint values[]={atomic_load(&ready),atomic_load(&enabled),atomic_load(&status_total),atomic_load(&status_planned),atomic_load(&status_hit),(jint)atomic_load(&config_revision),atomic_load(&status_score),atomic_load(&status_accuracy),atomic_load(&status_miss),atomic_load(&status_good),atomic_load(&status_bad),atomic_load(&status_final),atomic_load(&status_actual_score),atomic_load(&status_actual_accuracy),atomic_load(&status_actual_perfect),atomic_load(&status_actual_good),atomic_load(&status_actual_bad),atomic_load(&status_actual_miss),atomic_load(&status_actual_combo),atomic_load(&status_actual_early),atomic_load(&status_actual_late)};
    jintArray out=(*env)->NewIntArray(env,21);
    if(out)(*env)->SetIntArrayRegion(env,out,0,21,values);return out;
}
JNIEXPORT jboolean JNICALL Java_com_phigros_offline_NativeControls_configureForcedResult(JNIEnv *env,jclass cls,jboolean enabled,jint score,jint accuracy,jint perfect,jint good,jint bad,jint miss,jint combo,jboolean earlyLateOn,jint early,jint late,jint symbol) {
    (void)env;(void)cls;
    // Ranges are deliberately permissive: the whole point of this switch is to
    // display values a real run could not produce. Only values that would corrupt
    // the settlement screen's own arithmetic are rejected.
    if(score<0||score>99999999||accuracy<0||accuracy>10000)return JNI_FALSE;
    if(perfect<0||good<0||bad<0||miss<0||combo<0)return JNI_FALSE;
    if(perfect>999999||good>999999||bad>999999||miss>999999||combo>999999)return JNI_FALSE;
    if(early<0||late<0||early>999999||late>999999)return JNI_FALSE;
    if(symbol<0||symbol>2)return JNI_FALSE;
    atomic_store(&force_score,score);
    atomic_store(&force_accuracy,accuracy);
    atomic_store(&force_perfect,perfect);
    atomic_store(&force_good,good);
    atomic_store(&force_bad,bad);
    atomic_store(&force_miss,miss);
    atomic_store(&force_combo,combo);
    atomic_store(&force_early,early);
    atomic_store(&force_late,late);
    atomic_store(&force_early_late_on,earlyLateOn==JNI_TRUE);
    atomic_store(&force_symbol,symbol);
    atomic_store(&force_enabled,enabled==JNI_TRUE);
    return JNI_TRUE;
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm,void *reserved) {
    (void)vm;(void)reserved;
    struct timespec now;if(clock_gettime(CLOCK_MONOTONIC,&now)==0)chart_seed^=(uint32_t)now.tv_nsec^(uint32_t)now.tv_sec;
    pthread_t thread;
    if (!pthread_create(&thread,NULL,worker,NULL)) pthread_detach(thread);
    return JNI_VERSION_1_6;
}
#endif
