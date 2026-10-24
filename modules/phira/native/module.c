#define _GNU_SOURCE
#include <jni.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <link.h>
#include <pthread.h>
#include <android/log.h>
#include "frame-policy.h"
#include "profile.h"
#include "../../common/hook-batch.h"
typedef void(*LibraryLoaded)(const char*,void*);
typedef struct{uint32_t version;LabHook hook;LabUnhook unhook;} NativeAPI;
static LabBatch batch;static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static atomic_bool ready,armed,enabled,tainted,early_frame;
static atomic_uint frames;
static uintptr_t (*game_original)(void*,void*);
static uintptr_t game_update(void *scene,void *time_manager){
 pthread_mutex_lock(&lock);bool active=atomic_load(&armed);if(!active)atomic_store(&early_frame,true);pthread_mutex_unlock(&lock);
 if(!active)return game_original(scene,time_manager);
 bool autoplay=atomic_load(&enabled);if(autoplay)atomic_store(&tainted,true);
 lab_prepare_frame(scene,autoplay,atomic_load(&tainted));atomic_fetch_add(&frames,1);
 return game_original(scene,time_manager);
}
typedef struct{uintptr_t base;bool okay;} Range;
static int range(struct dl_phdr_info *info,size_t unused,void *data){
 (void)unused;Range *r=data;if(info->dlpi_addr!=r->base)return 0;
 for(size_t i=0;i<info->dlpi_phnum;i++){const ElfW(Phdr)*p=&info->dlpi_phdr[i];if(p->p_type==PT_LOAD&&(p->p_flags&PF_X)&&GAME_UPDATE_RVA>=p->p_vaddr&&GAME_UPDATE_RVA-p->p_vaddr<=p->p_memsz&&p->p_memsz-(GAME_UPDATE_RVA-p->p_vaddr)>=16)r->okay=true;}return 1;
}
static void loaded(const char *name,void *handle){
 if(!name||!handle)return;const char *leaf=strrchr(name,'/');leaf=leaf?leaf+1:name;if(strcmp(leaf,"libphira.so"))return;
 pthread_mutex_lock(&lock);if(atomic_load(&ready)||!batch.hook){pthread_mutex_unlock(&lock);return;}
 void *symbol=dlsym(handle,"Java_quad_1native_QuadNative_setDataPath");Dl_info info;
 if(!symbol||!dladdr(symbol,&info)||!info.dli_fbase){pthread_mutex_unlock(&lock);return;}
 Range r={.base=(uintptr_t)info.dli_fbase};dl_iterate_phdr(range,&r);void *target=(void*)(r.base+GAME_UPDATE_RVA);
 if(!r.okay||memcmp(target,game_prefix,sizeof(game_prefix))){__android_log_print(ANDROID_LOG_ERROR,"PhiraLab","Native profile mismatch; no activation");pthread_mutex_unlock(&lock);return;}
 if(batch_install(&batch,target,(void*)game_update,(void**)&game_original)==0){atomic_store(&ready,true);__android_log_print(ANDROID_LOG_INFO,"PhiraLab","Original offline-mode/autoplay adapter installed; autoplay off");}else{batch_rollback(&batch);}
 pthread_mutex_unlock(&lock);
}
__attribute__((visibility("default"),used)) LibraryLoaded native_init(const NativeAPI *api){if(!api||api->version<1||api->version>2||!api->hook||!api->unhook)return NULL;batch.hook=api->hook;batch.unhook=api->unhook;void *existing=dlopen("libphira.so",RTLD_NOW|RTLD_NOLOAD);if(existing){loaded("libphira.so",existing);dlclose(existing);}return loaded;}
JNIEXPORT jboolean JNICALL Java_com_phira_lab_LabNative_arm(JNIEnv *env,jclass type){(void)env;(void)type;pthread_mutex_lock(&lock);bool okay=atomic_load(&ready)&&!atomic_load(&early_frame);if(okay)atomic_store(&armed,true);pthread_mutex_unlock(&lock);return okay?JNI_TRUE:JNI_FALSE;}
JNIEXPORT void JNICALL Java_com_phira_lab_LabNative_disarm(JNIEnv *env,jclass type){(void)env;(void)type;atomic_store(&enabled,false);atomic_store(&armed,false);}
JNIEXPORT jboolean JNICALL Java_com_phira_lab_LabNative_setEnabled(JNIEnv *env,jclass type,jboolean value){(void)env;(void)type;if(!atomic_load(&ready)||!atomic_load(&armed))return JNI_FALSE;atomic_store(&enabled,value==JNI_TRUE);return JNI_TRUE;}
JNIEXPORT jboolean JNICALL Java_com_phira_lab_LabNative_setTemporaryRoot(JNIEnv *env,jclass type,jstring directory){(void)type;if(!directory||!atomic_load(&armed))return JNI_FALSE;const char *p=(*env)->GetStringUTFChars(env,directory,NULL);if(!p)return JNI_FALSE;size_t n=strnlen(p,1024);bool okay=n>0&&n<1024&&p[0]=='/'&&!strstr(p,"..")&&!strchr(p,'\n');if(okay)okay=setenv("TMPDIR",p,1)==0;(*env)->ReleaseStringUTFChars(env,directory,p);return okay?JNI_TRUE:JNI_FALSE;}
JNIEXPORT jintArray JNICALL Java_com_phira_lab_LabNative_status(JNIEnv *env,jclass type){(void)type;jint v[]={atomic_load(&ready),atomic_load(&armed),atomic_load(&enabled),atomic_load(&tainted),(jint)atomic_load(&frames)};jintArray out=(*env)->NewIntArray(env,5);if(out)(*env)->SetIntArrayRegion(env,out,0,5,v);return out;}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm,void *unused){(void)vm;(void)unused;return JNI_VERSION_1_6;}
