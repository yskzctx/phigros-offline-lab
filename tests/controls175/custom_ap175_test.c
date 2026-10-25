#define main previous_good_test_main
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wreturn-type"
#include "../controls174/good_timing173_test.c"
#pragma GCC diagnostic pop
#undef main
static bool continuation;
static bool ap_input(void *self,const void *method){
 (void)method;contacts++;void *data=pointer(self,case_type==0?0x40:0x38),*clock=pointer(self,0x28);
 if(!continuation)assert(fabsf(*number(clock,0x90)-*number(data,0x2c))<1e-6);
 if(case_type==0||case_type==3)assert(*byte(data,0x28));
 if(case_type==1)assert(*byte(self,0x60));
 if(case_type==2)assert(*byte(data,0x29)&&*byte(self,0x60));
 if(case_type==3){assert(*integer(self,0xb4)==2&&!*byte(self,0x8d));*byte(self,0x8e)=1;}
 return true;
}
int main(void){
 float delays[]={0,.001f,.5f,10.f};int kinds[]={1,2,4,3};
 for(case_type=0;case_type<4;case_type++)for(int k=0;k<4;k++){
  setup(0,.08f,.18f);*integer(note,0x10)=kinds[case_type];float real_time=1.f+delays[k];*number(progress,0x90)=real_time;continuation=false;
  assert(automatic(control,NULL,ap_input,case_type));assert(contacts==1&&*number(progress,0x90)==real_time&&selected[1]==0);
  if(case_type==3){continuation=true;*integer(control,0xb4)=0;*byte(control,0x8d)=1;*number(progress,0x90)=real_time+.2f;assert(automatic(control,NULL,ap_input,case_type));assert(*number(progress,0x90)==real_time+.2f);}
 }
 setup(0,.08f,.18f);*byte(judge,0x88)=1;assert(!automatic(control,NULL,ap_input,0)&&contacts==0&&!*byte(note,0x28));*byte(judge,0x88)=0;*byte(progress,0x8a)=0;assert(!automatic(control,NULL,ap_input,0)&&contacts==0&&!*byte(note,0x28));
 puts("PASS: shared Unity custom AP on four note kinds, late frames, Hold continuation, clock restoration and paused/blocked guards");
}
