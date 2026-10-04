#include <assert.h>
#include <stdio.h>
#define OFFLINE_HOST_TEST
#include "../src/offline.c"
static unsigned char score_obj[0x110],level_obj[0x80],list_obj[0x30],array_obj[0x20+300*8],notes[300][0x40],result_obj[0x48],control[0xc0],progress[0xb0];
static Vec3 last_position;static bool last_hold;
static void combo(void *s){int c=++*integer(s,0x4c);if(c>*integer(s,0x54))*integer(s,0x54)=c;}
static void fake_perfect(void *s,float code,float t,Vec3 p,bool h,const void *m){(void)code;(void)t;(void)m;(*integer(s,0x58))++;combo(s);last_position=p;last_hold=h;}
static void fake_good(void *s,float code,float t,Vec3 p,bool h,const void *m){(void)code;(void)t;(void)m;(*integer(s,0x5c))++;combo(s);last_position=p;last_hold=h;}
static void fake_miss(void *s,float code,const void *m){(void)code;(void)m;(*integer(s,0x64))++;*integer(s,0x4c)=0;}
static void fake_bad(void *s,float code,float t,const void *m){(void)code;(void)t;(void)m;(*integer(s,0x60))++;*integer(s,0x4c)=0;}
static void *fake_result(void *s,const void *m){(void)m;float a=(*integer(s,0x58)+0.65f**integer(s,0x5c))/300.f;
 *integer(result_obj,0x10)=(int)(900000.f*a+100000.f**integer(s,0x54)/300.f+0.5f);*number(result_obj,0x18)=a*100.f;return result_obj;}
static void setup(ControlConfig c){
 memset(score_obj,0,sizeof(score_obj));memset(result_obj,0,sizeof(result_obj));plan_last_count=-1;plan_score=NULL;memset(control,0,sizeof(control));memset(progress,0,sizeof(progress));
 *(void **)(score_obj+0x30)=level_obj;*(void **)(level_obj+0x38)=list_obj;*(void **)(list_obj+0x10)=array_obj;*integer(list_obj,0x18)=300;*(size_t *)(array_obj+0x18)=300;
 for(int i=0;i<300;i++){memset(notes[i],0,sizeof(notes[i]));*(void **)(array_obj+0x20+i*8)=notes[i];*number(notes[i],0x38)=(float)(i+1);*number(notes[i],0x2c)=1.f+i/10.f;}
 pending_config=c;perfect_original=fake_perfect;good_call=fake_good;miss_original=fake_miss;bad_call=fake_bad;result_original=fake_result;atomic_store(&ready,true);atomic_store(&enabled,true);
}
static void play(void){for(int i=1;i<=300;i++)perfect(score_obj,(float)i,0,(Vec3){1,2,3},true,NULL);}
static bool untouched_judge(void *s,const void *m){(void)m;void *note=pointer(s,0x40),*p=pointer(s,0x28);assert(!*byte(note,0x28));assert(*number(p,0x90)==2.f);miss(pointer(s,0x20),*number(note,0x38),NULL);return false;}
int main(void){
 ControlConfig c={.target=1000000,.accuracy=10000,.rule_count=2};c.rules[0]=(NoteRule){.judgment=1,.position=1,.start=100,.end=100,.count=1};c.rules[1]=(NoteRule){.judgment=2,.position=1,.start=50,.end=50,.count=1};setup(c);play();assert(*integer(score_obj,0x64)==1&&*integer(score_obj,0x5c)==1&&*integer(score_obj,0x58)==298);assert(atomic_load(&status_hit)==2);assert(last_position.x==1&&last_position.y==2&&last_position.z==3&&last_hold);
 c=(ControlConfig){.mode=1,.target=900000,.accuracy=10000};setup(c);play();int expected=atomic_load(&status_score);void *r=result(score_obj,NULL);assert(*integer(r,0x10)==expected&&abs(expected-900000)<=1600);assert(*integer(score_obj,0x64)+*integer(score_obj,0x5c)>0);assert(plan_score==NULL);
 c=(ControlConfig){.mode=2,.target=1000000,.accuracy=9000};setup(c);play();r=result(score_obj,NULL);assert(*number(r,0x18)>89.8f&&*number(r,0x18)<90.2f);
 c=(ControlConfig){.rule_count=1};c.rules[0]=(NoteRule){.judgment=1,.position=1,.start=1,.end=1,.count=1};setup(c);*(void **)(control+0x20)=score_obj;*(void **)(control+0x28)=progress;*(void **)(control+0x40)=notes[0];*byte(progress,0x8a)=1;*number(progress,0x90)=2.f;
 automatic(control,NULL,untouched_judge,0);assert(*integer(score_obj,0x64)==1&&atomic_load(&status_hit)==1); // real input flags and clock preserved for Miss
 setup(c);perfect(score_obj,1,0,(Vec3){0,0,0},false,NULL);pending_config.rules[0].judgment=2;pending_config.rules[0].start=50;play();assert(*integer(score_obj,0x5c)==0); // chart config frozen
 setup(c);atomic_store(&enabled,false);play();assert(*integer(score_obj,0x58)==300&&*integer(score_obj,0x64)==0);
 puts("PASS native dispatcher: simultaneous rules, natural missed input, score/accuracy via judgments, result not overwritten, disabled mode, config snapshot");
}
