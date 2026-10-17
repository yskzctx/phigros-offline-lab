#include <assert.h>
#include <stdio.h>
#define OFFLINE_HOST_TEST
#include "../../chapter-player/android-source/native/offline.c"
static unsigned char s[0x110],level[0x80],list[0x30],array[0x20+300*8],notes[300][0x40],out[0x48];
static void combo(void){int c=++*integer(s,0x4c);if(c>*integer(s,0x54))*integer(s,0x54)=c;}
static void p(void*a,float b,float c,Vec3 d,bool e,const void*f){(void)a;(void)b;(void)c;(void)d;(void)e;(void)f;(*integer(s,0x58))++;combo();}
static void g(void*a,float b,float c,Vec3 d,bool e,const void*f){(void)a;(void)b;(void)d;(void)e;(void)f;(*integer(s,0x5c))++;(*integer(s,c<0?0x68:0x6c))++;combo();}
static void m(void*a,float b,const void*c){(void)a;(void)b;(void)c;(*integer(s,0x64))++;*integer(s,0x4c)=0;}
static void b(void*a,float c,float d,const void*e){(void)a;(void)c;(void)d;(void)e;(*integer(s,0x60))++;*integer(s,0x4c)=0;}
static void *r(void*a,const void*b){(void)a;(void)b;
 float accuracy=(*integer(s,0x58)+0.65f**integer(s,0x5c))/300.f;
 *integer(out,0x10)=(int)(900000.f*accuracy+100000.f**integer(s,0x54)/300.f+0.5f);
 *number(out,0x18)=accuracy*100.f;
 memcpy(out+0x1c,s+0x58,24);*integer(out,0x34)=*integer(s,0x54);return out;
}
static void run(ControlConfig config){
 memset(s,0,sizeof(s));memset(out,0,sizeof(out));plan_score=NULL;plan_last_count=-1;
 *(void**)(s+0x30)=level;*(void**)(level+0x38)=list;*(void**)(list+0x10)=array;*integer(list,0x18)=300;*(size_t*)(array+0x18)=300;
 for(int i=0;i<300;i++){
  *(void**)(array+0x20+i*8)=notes[i];memset(notes[i],0,sizeof(notes[i]));*integer(notes[i],0x10)=1+i%4;
  *number(notes[i],0x38)=i+1;*number(notes[i],0x2c)=1.f+i*0.07f;*number(notes[i],0x1c)=i%4==2?1.3f:0;
 }
 pending_config=config;perfect_original=p;good_call=g;miss_original=m;bad_call=b;result_original=r;
 atomic_store(&ready,true);atomic_store(&enabled,true);ordinal(s,1.f);
 PlanTimeline t=plan_timeline_seed(note_timing,note_types,300,0.13f,0.18f,chart_plan_seed);
 for(int k=0;k<t.length;k++){
  PlanEvent event=t.events[k];int id=event.note,j=event.judgment;if(selected[id]!=j)continue;
  if(j==1)miss(s,id,NULL);
  else if(plan_is_good(j)&&(note_types[id]==1||note_types[id]==3))good(s,id,plan_good_early(j,id,chart_plan_seed)?-0.13f:0.13f,(Vec3){0},note_types[id]==3,NULL);
  else perfect(s,id,0,(Vec3){0},note_types[id]==3,NULL);
 }
 free(t.events);int predicted=atomic_load(&status_score),accuracy=atomic_load(&status_accuracy);
 result(s,NULL);
 assert(*integer(out,0x10)==predicted);assert(abs((int)(*number(out,0x18)*100.f+0.5f)-accuracy)<=1);
 assert(atomic_load(&status_actual_score)==*integer(out,0x10)&&atomic_load(&status_final)==1);
 assert(atomic_load(&status_actual_good)==*integer(s,0x5c)&&atomic_load(&status_actual_combo)==*integer(s,0x54));
 assert(atomic_load(&status_hit)==atomic_load(&status_planned));
}
int main(void){
 ControlConfig c={.rule_count=1};c.rules[0]=(NoteRule){.judgment=2,.position=6};run(c);
 assert(*integer(out,0x10)==685000&&atomic_load(&status_actual_accuracy)==6500&&*integer(out,0x20)==300);
 assert(atomic_load(&status_actual_early)>0&&atomic_load(&status_actual_late)>0);
 c.rules[0].judgment=4;run(c);assert(atomic_load(&status_actual_early)==300&&atomic_load(&status_actual_late)==0);
 c.rules[0].judgment=5;run(c);assert(atomic_load(&status_actual_early)==0&&atomic_load(&status_actual_late)==300);
 c=(ControlConfig){.mode=1,.target=900000,.accuracy=10000};run(c);assert(abs(*integer(out,0x10)-900000)<2000);
 c.mode=2;c.accuracy=9000;run(c);assert(abs(atomic_load(&status_actual_accuracy)-9000)<20);
 c=(ControlConfig){.rule_count=2,.mode=2,.accuracy=0};c.rules[0]=(NoteRule){.judgment=0,.position=2,.start=1,.end=20};c.rules[1]=(NoteRule){.judgment=1,.position=6};run(c);
 assert(*integer(out,0x1c)==20&&*integer(out,0x28)==280);
 puts("PASS: actual dispatch/readback, Good on all four types, all-Good 685000, timing-aware score/accuracy, Perfect protection");
}
