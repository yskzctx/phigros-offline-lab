#include <assert.h>
#include <stdio.h>
#include "../../chapter-player/android-source/native/controls-plan.h"
int main(void){
 // Long hold starts first but scores after the Miss and the following Tap.
 PlanTiming times[5]={{0},{0,10},{1,1},{2,2},{3,3}};
 unsigned char types[5]={0,3,1,1,1},p[5]={0,0,1,0,0};PlanEstimate e;
 PlanTimeline t=plan_timeline(times,types,4,0.13f,0.18f);
 estimate_timed(p,4,&e,&t);assert(e.max_combo==3&&e.score==750000);
 estimate_plan(p,4,&e);assert(e.max_combo==2&&e.score==725000);
 // A late Good occurs after a nearby Miss timeout, preserving its combo.
 times[1]=(PlanTiming){0,0};times[2]=(PlanTiming){-0.06f,-0.06f};types[1]=1;
 free(t.events);t=plan_timeline(times,types,4,0.13f,0.18f);p[1]=2;
 estimate_timed(p,4,&e,&t);assert(e.max_combo==3&&e.accuracy==6625);
 t.challenge=1;estimate_timed(p,4,&e,&t);assert(e.score==662500);
 memset(p,2,5);estimate_timed(p,4,&e,&t);assert(e.score==650000&&e.accuracy==6500);
 free(t.events);
 puts("PASS: hold-tail and delayed Good/Miss chronology instead of start order");
}
