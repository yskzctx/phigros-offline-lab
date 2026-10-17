#include <assert.h>
#include <stdio.h>
#include <math.h>
#define OFFLINE_HOST_TEST
#include "../../chapter-player/android-source/native/offline.c"
static unsigned char score_obj[0x110],level_obj[0x80],list_obj[0x30],array_obj[0x30],note_obj[0x40],control_obj[0xc0],progress_obj[0xb0],judge_obj[0x90],class_obj[0xc0],static_obj[0x10];
static int calls;
static bool simulate_hold_head(void *self,const void *method){
 (void)method;calls++;void *note=pointer(self,0x38),*progress=pointer(self,0x28);
 if(!*byte(note,0x28))return false;
 float difference=fabsf(*number(progress,0x90)-*number(note,0x2c));
 if(difference>=*number(static_obj,8))return false;
 *byte(self,0x8e)=1;*byte(self,0x90)=difference<*number(static_obj,4);
 return true;
}
int main(void){
 *(void **)(score_obj+0x30)=level_obj;*(void **)(level_obj+0x38)=list_obj;
 *(void **)(list_obj+0x10)=array_obj;*integer(list_obj,0x18)=1;*(size_t *)(array_obj+0x18)=1;
 *(void **)(array_obj+0x20)=note_obj;*number(note_obj,0x38)=1;*number(note_obj,0x2c)=10;
 *(void **)(control_obj+0x20)=score_obj;*(void **)(control_obj+0x28)=progress_obj;*(void **)(control_obj+0x38)=note_obj;
 *byte(progress_obj,0x8a)=1;*number(progress_obj,0x90)=1.1f;
 *(void **)(progress_obj+0x30)=judge_obj;*(void **)(judge_obj)=class_obj;*(void **)(class_obj+0xb8)=static_obj;
 *number(static_obj,4)=0.08f;*number(static_obj,8)=0.18f;*number(progress_obj,0x90)=10.2f;
 pending_config=(ControlConfig){.rule_count=1};pending_config.rules[0]=(NoteRule){.judgment=2,.position=6,.start=1,.end=1,.count=1};
 atomic_store(&ready,true);atomic_store(&enabled,true);
 automatic(control_obj,NULL,simulate_hold_head,3);
 assert(calls==1 && *byte(control_obj,0x8e));
 assert(!*byte(control_obj,0x90) && "planned Good hold head must enter original Good state, not Perfect");
 assert(fabsf(*number(progress_obj,0x90)-10.2f)<0.0001f);
 // A tighter judgment window must use its actual midpoint, not 0.13s.
 *byte(control_obj,0x8e)=0;*byte(note_obj,0x28)=0;*number(static_obj,4)=0.04f;*number(static_obj,8)=0.10f;
 automatic(control_obj,NULL,simulate_hold_head,3);assert(!*byte(control_obj,0x90));
 puts("PASS: Hold Good head state and real clock restoration");
}
