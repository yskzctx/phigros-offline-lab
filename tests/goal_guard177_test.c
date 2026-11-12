#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../src/controls-plan.h"

int main(void){
    unsigned char plan[101],types[101]={0};PlanEstimate e;
    memset(types,1,sizeof(types));

    ControlConfig c={.mode=1,.target=700000,.assist_guard=2};
    make_profile_typed(&c,100,123,plan,&e,types);
    assert(e.bad==0&&e.miss==0);

    c=(ControlConfig){.assist_guard=3};
    make_profile_typed(&c,100,123,plan,&e,types);
    assert(e.good==0&&e.bad==0&&e.miss==0);

    c=(ControlConfig){.mode=1,.target=700000,.guard_score=900000};
    make_profile_typed(&c,100,123,plan,&e,types);
    assert(e.score>=900000);

    c=(ControlConfig){.assist_guard=2,.rule_count=1};
    c.rules[0]=(NoteRule){.judgment=1,.position=1,.start=1,.end=1,.count=1};
    make_profile_typed(&c,100,123,plan,&e,types);
    assert(plan[1]==1&&e.miss==1);

    assert(assist_rescue_chance(0,0)==0);
    assert(assist_rescue_chance(50000,0)>assist_rescue_chance(1000,0));
    assert(assist_rescue_chance(1000000,0)==100);
    puts("PASS: explicit-rule priority, S/FC/AT guard, extra score floor and distance-to-target rescue weighting");
    return 0;
}
