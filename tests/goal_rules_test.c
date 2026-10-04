#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include "../src/controls-plan.h"
int main(void){
 static unsigned char plan[MAX_NOTES+1];
 ControlConfig c={.target=900000,.accuracy=9000,.mode=1};
 PlanEstimate e;make_profile(&c,300,12345,plan,&e);
 assert(abs(e.score-900000)<=1600);assert(e.good+e.miss+e.bad>0);
 c.mode=2;make_profile(&c,300,9876,plan,&e);assert(abs(e.accuracy-9000)<=20);
 c.mode=0;c.rule_count=3;
 c.rules[0]=(NoteRule){.judgment=1,.position=1,.start=100,.end=100,.count=1};
 c.rules[1]=(NoteRule){.judgment=2,.position=1,.start=50,.end=50,.count=1};
 c.rules[2]=(NoteRule){.judgment=3,.position=2,.start=50,.end=80,.count=1};
 make_profile(&c,300,99,plan,&e);assert(plan[100]==1&&plan[50]==2&&plan[51]==3&&plan[80]==3&&plan[81]==0);assert(e.miss==1&&e.good==1&&e.bad==30);
 c=(ControlConfig){.mode=0,.rule_count=2};
 c.rules[0]=(NoteRule){.judgment=2,.position=5,.start=20,.end=20,.count=15};
 c.rules[1]=(NoteRule){.judgment=1,.position=4,.start=1,.end=1,.count=17};
 for(int seed=1;seed<=100;seed++){make_profile(&c,300,(uint32_t)seed,plan,&e);assert(e.good==15&&e.miss==17);for(int i=20;i<35;i++)assert(plan[i]==2);}
 c.rules[1].count=20000;make_profile(&c,30,123,plan,&e);assert(e.good==11&&e.miss==19);
 c=(ControlConfig){.mode=0,.target=1000000,.accuracy=10000};make_profile(&c,300,1,plan,&e);assert(e.score==1000000&&e.accuracy==10000&&e.perfect==300);
 for(int n=1;n<=20000;n=n<100?n+1:n*2){for(int mode=1;mode<=2;mode++){c.mode=mode;c.target=900000;c.accuracy=9000;make_profile(&c,n,n,plan,&e);assert(e.perfect+e.good+e.bad+e.miss==n);for(int k=1;k<=n;k++)assert(plan[k]<=3);}}
 c=(ControlConfig){.mode=1,.target=0,.rule_count=1};c.rules[0]=(NoteRule){.judgment=2,.position=6,.start=1,.end=1,.count=1};make_profile(&c,300,1,plan,&e);assert(e.good==300&&e.miss==0); // goal cannot overwrite rules
 puts("PASS natural score/accuracy planning, rule priority, small/large charts, explicit constraints");
}
