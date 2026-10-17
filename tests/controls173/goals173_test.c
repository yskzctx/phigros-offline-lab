#include <assert.h>
#include <stdio.h>
#include "../../chapter-player/android-source/native/controls-plan.h"
int main(void){
 unsigned char p[301],types[301];PlanEstimate e;
 for(int i=1;i<=300;i++)types[i]=(unsigned char)(1+(i-1)%4);
 ControlConfig c={.mode=2,.accuracy=9000,.target=1000000};
 for(int type=2;type<=4;type+=2){
  c=(ControlConfig){.rule_count=1};c.rules[0]=(NoteRule){.judgment=2,.position=6,.note_type=type};
  make_profile_typed(&c,300,42,p,&e,types);assert(e.good==75);
  for(int i=1;i<=300;i++)assert(p[i]==(types[i]==type?2:0));
 }
 c=(ControlConfig){.mode=2,.accuracy=0,.rule_count=2};
 c.rules[0]=(NoteRule){.judgment=0,.position=2,.start=1,.end=20};
 c.rules[1]=(NoteRule){.judgment=1,.position=6};
 make_profile_typed(&c,300,123,p,&e,types);
 for(int i=1;i<=300;i++)assert(p[i]==(i<=20?0:1));
 memset(p,2,301);estimate_plan(p,300,&e);
 assert(e.accuracy==6500&&e.score==685000);
 puts("PASS: synthetic Drag/Flick Good preserved, Perfect precedence, audited Good 0.65");
}
