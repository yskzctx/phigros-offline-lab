#define _GNU_SOURCE
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdatomic.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <unistd.h>
#include <pthread.h>
#ifndef SKIN_HOST_TEST
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <link.h>
#include <dlfcn.h>
#include <jni.h>
#include <android/log.h>
#include "skin-hooks.h"
static uintptr_t image;
#include "native-hook-impl.h"
#endif

typedef struct {float x,y,w,h;} SkinRect;
typedef struct {float x,y;} SkinVec2;
typedef struct {int slot;float ppu;SkinVec2 pivot;void *sprite;uint32_t handle;} SpriteEntry;
static const char *texture_names[12]={"click.png","drag.png","flick.png","hold-head.png","hold-body.png","hold-tail.png","click_mh.png","drag_mh.png","flick_mh.png","hold-head_mh.png","hold-body_mh.png","hold-tail_mh.png"};
static void *texture_cache[12];
static uint32_t texture_handles[12];
static SpriteEntry sprite_cache[64];
static int cache_count;
static atomic_int skin_ready,loaded_textures,loaded_sprites,skin_errors;
static atomic_bool skin_enabled;
static char pack_directory[512];
static pthread_mutex_t skin_lock=PTHREAD_MUTEX_INITIALIZER;

static void *(*sprite_texture)(void *,const void *);
static SkinRect (*sprite_rect)(void *,const void *);
static SkinVec2 (*sprite_pivot)(void *,const void *);
static float (*sprite_ppu)(void *,const void *);
static int (*texture_width)(void *,const void *),(*texture_height)(void *,const void *);
static void *(*create_sprite)(void *,SkinRect,SkinVec2,float,uint32_t,int,const void *);
static void *(*renderer_get)(void *,const void *);
static void (*renderer_set)(void *,void *,const void *);
static bool (*object_alive)(void *,const void *);
static void (*write_barrier)(void *,void **,void *);
static uint32_t (*gc_new)(void *,bool);
static void (*gc_free)(uint32_t);
static void *(*array_new)(void *,size_t);
static void *(*load_texture_impl)(const char *,void *);
static void (*moves_original[4])(void *,const void *);

static void *skin_ptr(void *obj,size_t offset){return *(void **)((char *)obj+offset);}
static int texture_slot_from_name(const char *name){for(int i=0;i<12;i++)if(!strcmp(name,texture_names[i]))return i;return -1;}
static bool chord(void *self,int type){
    void *note=skin_ptr(self,type==1?0x40:0x38),*level=skin_ptr(self,0x30);
    if(!note||!level)return false;
    void *list=skin_ptr(level,0x38);if(!list)return false;
    int n=*(int *)((char *)list+0x18);void *array=skin_ptr(list,0x10);
    if(!array||n<2||n>20000||*(size_t *)((char *)array+0x18)<(size_t)n)return false;
    float t=*(float *)((char *)note+0x2c);if(!isfinite(t))return false;
    int lo=0,hi=n;
    while(lo<hi){int mid=lo+(hi-lo)/2;void *p=skin_ptr(array,0x20+(size_t)mid*8);if(!p)return false;float due=*(float *)((char *)p+0x2c);if(due<t)lo=mid+1;else hi=mid;}
    if(lo+1>=n)return false;
    void *a=skin_ptr(array,0x20+(size_t)lo*8),*b=skin_ptr(array,0x20+(size_t)(lo+1)*8);
    return a&&b&&*(float *)((char *)a+0x2c)==t&&*(float *)((char *)b+0x2c)==t;
}
static void update_renderer(void *renderer,void *sprite){
    if(!renderer||!sprite||(object_alive&&!object_alive(renderer,NULL)))return;
    if(renderer_get(renderer,NULL)!=sprite)renderer_set(renderer,sprite,NULL);
}
static void *skin_sprite(int slot,void *original){
    if(!original||(object_alive&&!object_alive(original,NULL)))return NULL;
    for(int i=0;i<cache_count;i++)if(sprite_cache[i].sprite==original&&sprite_cache[i].slot==slot)return original;
    SkinRect old=sprite_rect(original,NULL);SkinVec2 pivot=sprite_pivot(original,NULL);float ppu=sprite_ppu(original,NULL);
    if(!isfinite(old.w)||old.w<=0.f||!isfinite(ppu)||ppu<=0.f||old.h<=0.f)return NULL;
    void *tex=texture_cache[slot];
    if(!tex){tex=load_texture_impl(texture_names[slot],original);if(!tex){atomic_fetch_add(&skin_errors,1);return NULL;}texture_cache[slot]=tex;if(!texture_handles[slot])texture_handles[slot]=gc_new(tex,false);atomic_fetch_add(&loaded_textures,1);}
    int w=texture_width(tex,NULL),h=texture_height(tex,NULL);if(w<1||h<1||w>4096||h>4096)return NULL;
    float scaled=ppu*(float)w/old.w;pivot.x/=old.w;pivot.y/=old.h;
    if(!isfinite(scaled)||!isfinite(pivot.x)||!isfinite(pivot.y)||scaled<=0.f)return NULL;
    for(int i=0;i<cache_count;i++){SpriteEntry *e=&sprite_cache[i];if(e->sprite&&e->slot==slot&&e->ppu==scaled&&e->pivot.x==pivot.x&&e->pivot.y==pivot.y)return e->sprite;}
    if(cache_count>=64){atomic_fetch_add(&skin_errors,1);return NULL;}
    // FullRect=0 (157 enum metadata) preserves the requested rectangle instead
    // of cropping bounds to alpha, keeping the original note's world width.
    void *sprite=create_sprite(tex,(SkinRect){0,0,(float)w,(float)h},pivot,scaled,0,0,NULL);
    if(!sprite){atomic_fetch_add(&skin_errors,1);return NULL;}
    SpriteEntry *entry=&sprite_cache[cache_count++];*entry=(SpriteEntry){slot,scaled,pivot,sprite,gc_new(sprite,false)};atomic_fetch_add(&loaded_sprites,1);return sprite;
}
static void apply_control(void *self,int type){
    if(!self||!atomic_load(&skin_enabled)||atomic_load(&skin_ready)!=1)return;
    int variant=chord(self,type)?6:0;
    if(type!=3){int slot=variant+(type==1?0:type==2?1:2);void *original=skin_ptr(self,0x50),*sprite=skin_sprite(slot,original);if(!sprite)return;
        if(original!=sprite)write_barrier(self,(void **)((char *)self+0x50),sprite);
        update_renderer(skin_ptr(self,0x68),sprite);return;
    }
    void *old=skin_ptr(self,0x50);if(!old)return;size_t count=*(size_t *)((char *)old+0x18);if(count<3||count>16)return;
    void *parts[3];bool same=true;
    for(int i=0;i<3;i++){void *original=skin_ptr(old,0x20+(size_t)i*8);parts[i]=skin_sprite(variant+3+i,original);if(!parts[i])return;if(parts[i]!=original)same=false;}
    if(!same){void *sprite_class=skin_ptr(skin_ptr(old,0x20),0);void *replacement=array_new(sprite_class,count);if(!replacement)return;uint32_t root=gc_new(replacement,false);
        for(size_t i=0;i<count;i++){void *value=i<3?parts[i]:skin_ptr(old,0x20+i*8);write_barrier(replacement,(void **)((char *)replacement+0x20+i*8),value);}
        write_barrier(self,(void **)((char *)self+0x50),replacement);if(gc_free)gc_free(root);
    }
    update_renderer(skin_ptr(self,0x98),parts[0]);update_renderer(skin_ptr(self,0xa0),parts[1]);update_renderer(skin_ptr(self,0xa8),parts[2]);
}
static void move_click(void *s,const void *m){apply_control(s,1);moves_original[0](s,m);}
static void move_drag(void *s,const void *m){apply_control(s,2);moves_original[1](s,m);}
static void move_flick(void *s,const void *m){apply_control(s,4);moves_original[2](s,m);}
static void move_hold(void *s,const void *m){apply_control(s,3);moves_original[3](s,m);}

#ifndef SKIN_HOST_TEST
static void *(*object_new)(void *);
static const void *(*get_corlib)(void);
static void *(*class_from_name)(const void *,const char *,const char *);
static void (*texture_ctor)(void *,int,int,const void *);
static bool (*load_image)(void *,void *,bool,const void *);
static void *native_texture(const char *name,void *original){
    if(texture_slot_from_name(name)<0)return NULL;
    char path[640];pthread_mutex_lock(&skin_lock);int len=snprintf(path,sizeof(path),"%s/%s",pack_directory,name);pthread_mutex_unlock(&skin_lock);if(len<0||(size_t)len>=sizeof(path))return NULL;
    int fd=open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);if(fd<0)return NULL;struct stat st;if(fstat(fd,&st)||!S_ISREG(st.st_mode)||st.st_size<33||st.st_size>24*1024*1024){close(fd);return NULL;}
    void *byte_class=class_from_name(get_corlib(),"System","Byte"),*old_texture=sprite_texture(original,NULL);if(!byte_class||!old_texture){close(fd);return NULL;}
    void *bytes=array_new(byte_class,(size_t)st.st_size);if(!bytes){close(fd);return NULL;}uint32_t bytes_root=gc_new(bytes,true);
    size_t done=0;while(done<(size_t)st.st_size){ssize_t n=read(fd,(char *)bytes+0x20+done,(size_t)st.st_size-done);if(n<=0){close(fd);gc_free(bytes_root);return NULL;}done+=(size_t)n;}close(fd);
    void *tex=object_new(skin_ptr(old_texture,0));if(!tex){gc_free(bytes_root);return NULL;}uint32_t tex_root=gc_new(tex,false);
    texture_ctor(tex,2,2,NULL);bool okay=load_image(tex,bytes,true,NULL);gc_free(bytes_root);
    if(!okay){gc_free(tex_root);return NULL;}
    // Transfer the temporary root to the cache without an unrooted handoff.
    texture_handles[texture_slot_from_name(name)]=tex_root;return tex;
}
static void *skin_worker(void *unused){
    (void)unused;for(int i=0;i<1800&&!image;i++){dl_iterate_phdr(find_image,NULL);if(!image)usleep(100000);}if(!image)return NULL;
    for(size_t i=0;i<sizeof(skin_hooks)/sizeof(skin_hooks[0]);i++)if(memcmp((void *)(image+skin_hooks[i].rva),skin_hooks[i].prefix,16)){atomic_store(&skin_ready,-1);return NULL;}
    void *lib=dlopen("libil2cpp.so",RTLD_NOW|RTLD_NOLOAD);if(!lib){atomic_store(&skin_ready,-1);return NULL;}
#define API(field,name) do{field=(void *)dlsym(lib,name);if(!field){atomic_store(&skin_ready,-1);return NULL;}}while(0)
    API(object_new,"il2cpp_object_new");API(array_new,"il2cpp_array_new");API(get_corlib,"il2cpp_get_corlib");API(class_from_name,"il2cpp_class_from_name");API(gc_new,"il2cpp_gchandle_new");API(gc_free,"il2cpp_gchandle_free");API(write_barrier,"il2cpp_gc_wbarrier_set_field");
#undef API
    sprite_texture=(void *(*)(void *,const void *))(image+SPRITE_TEXTURE_RVA);sprite_rect=(SkinRect (*)(void *,const void *))(image+SPRITE_RECT_RVA);sprite_pivot=(SkinVec2 (*)(void *,const void *))(image+SPRITE_PIVOT_RVA);sprite_ppu=(float (*)(void *,const void *))(image+SPRITE_PPU_RVA);
    texture_width=(int (*)(void *,const void *))(image+TEXTURE_WIDTH_RVA);texture_height=(int (*)(void *,const void *))(image+TEXTURE_HEIGHT_RVA);create_sprite=(void *(*)(void *,SkinRect,SkinVec2,float,uint32_t,int,const void *))(image+SPRITE_CREATE_RVA);
    renderer_get=(void *(*)(void *,const void *))(image+RENDERER_GET_RVA);renderer_set=(void (*)(void *,void *,const void *))(image+RENDERER_SET_RVA);object_alive=(bool (*)(void *,const void *))(image+OBJECT_ALIVE_RVA);
    texture_ctor=(void (*)(void *,int,int,const void *))(image+TEXTURE_CTOR_RVA);load_image=(bool (*)(void *,void *,bool,const void *))(image+LOAD_IMAGE_RVA);load_texture_impl=native_texture;
    void *changes[]={(void *)move_click,(void *)move_drag,(void *)move_flick,(void *)move_hold};
    for(size_t i=0;i<4;i++)if(install(&skin_hooks[i],changes[i],(void **)&moves_original[i])){atomic_store(&skin_ready,-1);return NULL;}
    atomic_store(&skin_ready,1);__android_log_print(ANDROID_LOG_INFO,"PhigrosOffline","skin.renderer.ready; local textures only");return NULL;
}
JNIEXPORT jboolean JNICALL Java_com_phigros_offline_SkinController_configure(JNIEnv *env,jclass cls,jstring directory){
    (void)cls;if(!directory)return JNI_FALSE;const char *path=(*env)->GetStringUTFChars(env,directory,NULL);if(!path)return JNI_FALSE;
    size_t n=strnlen(path,sizeof(pack_directory));bool valid=n>0&&n<sizeof(pack_directory)&&path[0]=='/'&&!strstr(path,"..")&&!strchr(path,'\n')&&!strchr(path,'\r');
    if(valid){pthread_mutex_lock(&skin_lock);memcpy(pack_directory,path,n+1);pthread_mutex_unlock(&skin_lock);atomic_store(&skin_enabled,true);}
    (*env)->ReleaseStringUTFChars(env,directory,path);return valid?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT jintArray JNICALL Java_com_phigros_offline_SkinController_status(JNIEnv *env,jclass cls){(void)cls;jint values[]={atomic_load(&skin_ready),atomic_load(&loaded_textures),atomic_load(&loaded_sprites),atomic_load(&skin_errors)};jintArray out=(*env)->NewIntArray(env,4);if(out)(*env)->SetIntArrayRegion(env,out,0,4,values);return out;}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm,void *reserved){(void)vm;(void)reserved;pthread_t thread;if(!pthread_create(&thread,NULL,skin_worker,NULL))pthread_detach(thread);return JNI_VERSION_1_6;}
#endif
