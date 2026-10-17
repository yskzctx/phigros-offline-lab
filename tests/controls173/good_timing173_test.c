#include <assert.h>
#include <math.h>
#include <stdio.h>
#define OFFLINE_HOST_TEST
#include "../../chapter-player/android-source/native/offline.c"
static unsigned char s[0x110],level[0x80],list[0x30],array[0x28],note[0x40],control[0xc0],progress[0xb0],judge[0x90],klass[0xc0],fields[0x10];
static float contact;static int contacts,case_type;
static bool head(void *self,const void *method){(void)method;
 void *n=pointer(self,case_type==0?0x40:0x38),*p=pointer(self,0x28);if(!*byte(n,0x28))return false;
 contact=*number(p,0x90)-*number(n,0x2c);contacts++;
 assert(fabsf(contact)>*number(fields,4)&&fabsf(contact)<*number(fields,8));
 *byte(self,0x8e)=1;*byte(self,0x90)=0;return true;
}
static void setup(int judgment,float perfect,float good_limit){
 memset(control,0,sizeof(control));memset(note,0,sizeof(note));contacts=0;plan_score=NULL;plan_last_count=-1;
 *(void**)(s+0x30)=level;*(void**)(s+0x90)=progress;*(void**)(level+0x38)=list;
 *(void**)(list+0x10)=array;*integer(list,0x18)=1;*(size_t*)(array+0x18)=1;*(void**)(array+0x20)=note;
 *integer(note,0x10)=3;*number(note,0x2c)=1;*number(note,0x38)=1;*number(note,0x1c)=1;
 *(void**)(control+0x20)=s;*(void**)(control+0x28)=progress;*(void**)(control+0x38)=note;*(void**)(control+0x40)=note;
 *(void**)(progress+0x30)=judge;*(void**)judge=klass;*(void**)(klass+0xb8)=fields;*byte(progress,0x8a)=1;
 *number(fields,4)=perfect;*number(fields,8)=good_limit;
 pending_config=(ControlConfig){.rule_count=1};pending_config.rules[0]=(NoteRule){.judgment=judgment,.position=6};
 atomic_store(&ready,true);atomic_store(&enabled,true);
}
int main(void){
 for(case_type=0;case_type<=3;case_type+=3)for(int j=4;j<=5;j++)for(int challenge=0;challenge<=1;challenge++){
  float perfect=challenge?0.04f:0.08f,limit=challenge?0.10f:0.18f;
  setup(j,perfect,limit);*integer(note,0x10)=case_type==0?1:3;float desired=1.f+(j==4?-1.f:1.f)*(perfect+limit)*0.5f;
  *number(progress,0x90)=desired-0.001f;automatic(control,NULL,head,case_type);assert(contacts==0);
  *number(progress,0x90)=desired+0.001f;automatic(control,NULL,head,case_type);assert(contacts==1);
  assert((j==4&&contact<0)||(j==5&&contact>0));assert(fabsf(*number(progress,0x90)-(desired+0.001f))<0.00001f);
 }
 int early=0,late=0;for(int i=1;i<=1000;i++)if(plan_good_early(2,i,123))early++;else late++;
 assert(early>350&&late>350);
 puts("PASS: Early before due, Late after due, active Good window, Hold state, restored clock and mixed timing");
}
