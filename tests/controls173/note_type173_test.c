#include <assert.h>
#include <stdio.h>
#include "../../chapter-player/android-source/native/controls-plan.h"
int main(void){
 unsigned char types[301]={0},plan[301];for(int i=1;i<=300;i++)types[i]=(unsigned char)(1+(i-1)%4);
 PlanEstimate estimate;ControlConfig c={.rule_count=1};
 c.rules[0]=(NoteRule){.judgment=2,.position=6,.start=1,.end=1,.count=1,.note_type=3};
 make_profile_typed(&c,300,1,plan,&estimate,types);assert(estimate.good==75);
 for(int i=1;i<=300;i++)assert(plan[i]==(types[i]==3?2:0));
 for(int type=1;type<=4;type++){
  c.rules[0]=(NoteRule){.judgment=1,.position=4,.start=1,.end=1,.count=17,.note_type=type};
  for(int seed=1;seed<=100;seed++){make_profile_typed(&c,300,seed,plan,&estimate,types);assert(estimate.miss==17);for(int i=1;i<=300;i++)if(plan[i])assert(types[i]==type);}
 }
 c.rules[0]=(NoteRule){.judgment=1,.position=2,.start=50,.end=80,.count=1,.note_type=4};
 make_profile_typed(&c,300,1,plan,&estimate,types);assert(estimate.miss==8);assert(plan[52]==1&&plan[50]==0&&plan[80]==1);
 c.rules[0]=(NoteRule){.judgment=1,.position=3,.start=1,.end=1,.count=1,.note_type=3,.length=3,.indices={2,3,7}};
 make_profile_typed(&c,300,1,plan,&estimate,types);assert(estimate.miss==2&&plan[2]==0&&plan[3]==1&&plan[7]==1);
 c.rule_count=2;c.rules[0]=(NoteRule){.judgment=2,.position=6,.note_type=3};c.rules[1]=(NoteRule){.judgment=1,.position=6};
 make_profile_typed(&c,300,1,plan,&estimate,types);assert(estimate.good==75&&estimate.miss==225);
 c.rule_count=1;c.rules[0]=(NoteRule){.judgment=1,.position=4,.count=20000,.note_type=4};make_profile_typed(&c,2,1,plan,&estimate,types);assert(estimate.miss==0);
 c.rules[0].note_type=0;make_profile_typed(&c,300,1,plan,&estimate,types);assert(estimate.miss==300);
 puts("PASS: note type filters, eligible random counts, global numbering, overlapping priority, empty type and legacy all");
}
