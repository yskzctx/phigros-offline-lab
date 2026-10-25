#include <assert.h>
#include <stdio.h>
#define OFFLINE_HOST_TEST
#include "../../chapter-player/android-source/native/offline.c"
static unsigned char score_obj[0x110],level_obj[0x80],list_obj[0x30];
static unsigned char array_a[0x20+MAX_NOTES*8],array_b[0x20+MAX_NOTES*8],notes_a[MAX_NOTES][0x40],notes_b[MAX_NOTES][0x40];
int main(void){
 *(void**)(score_obj+0x30)=level_obj;*(void**)(level_obj+0x38)=list_obj;*(void**)(list_obj+0x10)=array_a;*integer(list_obj,0x18)=MAX_NOTES;*(size_t*)(array_a+0x18)=MAX_NOTES;*(size_t*)(array_b+0x18)=MAX_NOTES;
 for(int k=0;k<MAX_NOTES;k++){*(void**)(array_a+0x20+k*8)=notes_a[k];*(void**)(array_b+0x20+k*8)=notes_b[k];*integer(notes_a[k],0x10)=1;*integer(notes_b[k],0x10)=1;*number(notes_a[k],0x38)=k+1;*number(notes_b[k],0x38)=k+1;*number(notes_a[k],0x2c)=k*.01f;*number(notes_b[k],0x2c)=k*.01f;}
 *integer(notes_a[2],0x10)=3;*integer(notes_b[0],0x10)=3;*number(notes_a[2],0x1c)=1;*number(notes_b[0],0x1c)=1;
 pending_config=(ControlConfig){.rule_count=1};pending_config.rules[0]=(NoteRule){.judgment=1,.position=6,.note_type=3};plan_score=NULL;plan_last_count=-1;
 assert(ordinal(score_obj,1)==1&&selected[3]==1&&selected[1]==0);
 // Same score/list/count but a different item buffer must invalidate the plan.
 *(void**)(list_obj+0x10)=array_b;assert(ordinal(score_obj,1)==1);assert(selected[1]==1&&selected[3]==0);
 for(int k=MAX_NOTES;k>=1;k--)assert(ordinal(score_obj,(float)k)==k);
 assert(ordinal(score_obj,-123)==0&&ordinal(score_obj,NAN)==0);
 puts("PASS: changed note buffer invalidates cached rule plan; all 20000 identifiers and missing/NaN lookups");
}
