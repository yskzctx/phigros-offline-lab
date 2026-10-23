#define main good_timing_previous_main
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wreturn-type"
#include "good_timing173_test.c"
#pragma GCC diagnostic pop
#undef main
static int bads,misses,sounds;
static float bad_time;
static void bad_result(void *score,float code,float time,const void *m){(void)code;(void)m;assert(score==s);bads++;bad_time=time;}
static void missed(void *score,float code,const void *m){(void)score;(void)code;(void)m;misses++;}
static bool tap_judge(void *self,const void *m){(void)m;
 float diff=*number(pointer(self,0x28),0x90)-*number(note,0x2c);
 assert(*byte(note,0x28));
 if(fabsf(diff)<*number(fields,8)){sounds++;return true;}
 assert(diff<0);bad_result(s,1,diff,NULL);return true;
}
static bool non_tap_judge(void *self,const void *m){(void)m;
 assert(!*byte(note,0x28)&&!*byte(note,0x29)&&!*byte(self,0x60));
 assert(*number(pointer(self,0x28),0x90)>1.22f);
 miss(s,1,NULL);return case_type!=3||*number(pointer(self,0x28),0x90)>*number(note,0x2c)+*number(note,0x1c)+.25f;
}
int main(void){
 PlanTiming timing[2]={{0},{1,5}};unsigned char kinds[2]={0,3};
 PlanTimeline timeline=plan_timeline_seed(timing,kinds,1,.13f,.18f,1);
 for(int k=0;k<timeline.length;k++)if(timeline.events[k].judgment==3)assert(fabsf(timeline.events[k].time-.8f)<.00001f);
 free(timeline.events);
 bad_call=bad_result;miss_original=missed;
 for(int type=0;type<4;type++){case_type=type;
  setup(3,.08f,.18f);*integer(note,0x10)=type==0?1:type==1?2:type==2?4:3;
  if(type==3)*number(note,0x1c)=10.f;
  *number(fields,12)=.22f;bads=misses=sounds=0;
  *number(progress,0x90)=.79f;assert(!automatic(control,NULL,type?non_tap_judge:tap_judge,type));assert(!bads&&!sounds);
  *number(progress,0x90)=.801f;assert(automatic(control,NULL,type?non_tap_judge:tap_judge,type));
  assert(bads==1&&misses==0&&sounds==0&&bad_time<-.18f&&bad_time>-.22f);
  assert(fabsf(*number(progress,0x90)-.801f)<.00001f);assert(consumed[1]);
 }
 puts("PASS: Bad before due on all four note types, no normal hit sounds, original Tap Bad path, scoped forced judgments, restored clock");
}
