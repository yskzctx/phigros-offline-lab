#define _GNU_SOURCE
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#ifndef PRIVACY_HOST_TEST
#include <sys/mman.h>
#include <link.h>
#include <jni.h>
#include <android/log.h>
#include "privacy-hooks.h"
static uintptr_t image;
#include "native-hook-impl.h"
#endif
typedef void (*start_fn)(void *,const void *);
static start_fn stats_original,setting_original;
static void *(*component_object)(void *,const void *);
static void (*set_active)(void *,bool,const void *);
static void *(*get_url)(void *,const void *);
static int64_t (*response_original)(void *,const void *);
static int32_t (*result_original)(void *,const void *);
static void *ptr(void *obj,size_t offset){return *(void **)((char *)obj+offset);}
static void hide_component(void *component){if(component){void *obj=component_object(component,NULL);if(obj)set_active(obj,false,NULL);}}
static void hide_account(void *self,const void *method){
    (void)method;if(!self)return;
    void *panel=ptr(self,0x98);if(panel)set_active(panel,false,NULL);
    panel=ptr(self,0xa0);if(panel)set_active(panel,false,NULL);
    size_t fields[]={0xa8,0xb0,0xb8,0xd0,0xe0,0x1a0,0x1a8,0x1b0,0x1b8};
    for(size_t i=0;i<sizeof(fields)/sizeof(fields[0]);i++)hide_component(ptr(self,fields[i]));
}
static void stats_start(void *self,const void *method){stats_original(self,method);hide_account(self,method);}
static void no_login(void *self,const void *method){(void)self;(void)method;}
static void setting_start(void *self,const void *method){setting_original(self,method);if(self)hide_component(ptr(self,0x130));}
static bool external_http(void *request){
    if(!request)return false;
    void *s=get_url(request,NULL);if(!s)return false;
    int n=*(int *)((char *)s+0x10);uint16_t *text=(uint16_t *)((char *)s+0x14);
    if(n<7||n>32768)return false;
    const char *p="http://",*q="https://";
    bool http=true,https=n>=8;
    for(int i=0;i<7;i++)if(text[i]!=(uint16_t)p[i])http=false;
    for(int i=0;https&&i<8;i++)if(text[i]!=(uint16_t)q[i])https=false;
    return http||https;
}
static int64_t response_code(void *self,const void *method){return external_http(self)?403:response_original(self,method);}
static int32_t request_result(void *self,const void *method){int32_t result=result_original(self,method);return result!=0&&external_http(self)?3:result;}
#ifndef PRIVACY_HOST_TEST
static void *privacy_worker(void *unused){
    (void)unused;
    for(int i=0;i<1800&&!image;i++){dl_iterate_phdr(find_image,NULL);if(!image)usleep(100000);}
    if(!image)return NULL;
    for(size_t i=0;i<sizeof(privacy_hooks)/sizeof(privacy_hooks[0]);i++)if(memcmp((void *)(image+privacy_hooks[i].rva),privacy_hooks[i].prefix,16)){
        __android_log_print(ANDROID_LOG_ERROR,"PhigrosOffline","Offline UI version mismatch; refusing unsupported hooks");return NULL;
    }
    component_object=(void *(*)(void *,const void *))(image+COMPONENT_OBJECT_RVA);
    set_active=(void (*)(void *,bool,const void *))(image+SET_ACTIVE_RVA);
    get_url=(void *(*)(void *,const void *))(image+GET_URL_RVA);
    void *changes[]={(void *)stats_start,(void *)hide_account,(void *)no_login,(void *)setting_start,(void *)response_code,(void *)request_result};
    void *discard[2];void **originals[]={(void **)&stats_original,&discard[0],&discard[1],(void **)&setting_original,(void **)&response_original,(void **)&result_original};
    for(size_t i=0;i<sizeof(changes)/sizeof(changes[0]);i++)if(install(&privacy_hooks[i],changes[i],originals[i])){
        __android_log_print(ANDROID_LOG_ERROR,"PhigrosOffline","Offline UI hook installation failed: %zu",i);return NULL;
    }
    __android_log_print(ANDROID_LOG_INFO,"PhigrosOffline","offline.login.hidden; unity.http.local403; no INTERNET permission");return NULL;
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm,void *reserved){(void)vm;(void)reserved;pthread_t thread;if(!pthread_create(&thread,NULL,privacy_worker,NULL))pthread_detach(thread);return JNI_VERSION_1_6;}
#endif
