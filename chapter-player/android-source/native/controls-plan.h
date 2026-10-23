#ifndef CONTROLS_PLAN_H
#define CONTROLS_PLAN_H
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#define MAX_NOTES 20000
#define MAX_INDICES 1024
#define MAX_RULES 16
#define MAX_CONFIG_WORDS (2+MAX_RULES*(7+MAX_INDICES))
typedef struct {int judgment,position,start,end,count,length,note_type;int indices[MAX_INDICES];} NoteRule;
typedef struct {int mode,target,accuracy,rule_count;NoteRule rules[MAX_RULES];} ControlConfig;
typedef struct {int score,accuracy,perfect,good,bad,miss,max_combo;} PlanEstimate;
typedef struct {float start,end;} PlanTiming;
typedef struct {float time;int note,judgment;} PlanEvent;
typedef struct {PlanEvent *events;int length,challenge,denominator;} PlanTimeline;
static inline int plan_is_good(int j){return j==2||j==4||j==5;}
static inline int plan_good_early(int j,int id,uint32_t seed){
    uint32_t h=(uint32_t)id*0x9e3779b9u^seed;h^=h>>16;h*=0x85ebca6bu;h^=h>>13;
    return j==4||(j==2&&seed&&(h&1));
}
static inline int compare_events(const void *aa,const void *bb){
    const PlanEvent *a=aa,*b=bb;
    if(a->time<b->time)return -1;
    if(a->time>b->time)return 1;
    if(a->note!=b->note)return a->note<b->note?-1:1;
    return a->judgment-b->judgment;
}
static inline PlanTimeline plan_timeline_windows(const PlanTiming *times,const unsigned char *types,int n,float good_delay,float miss_delay,float bad_delay,uint32_t seed){
    PlanTimeline t={0};if(!times||n<1||n>MAX_NOTES)return t;
    t.events=malloc((size_t)n*6*sizeof(PlanEvent));if(!t.events)return t;
    for(int i=1;i<=n;i++){
        if(!isfinite(times[i].start)||!isfinite(times[i].end)){free(t.events);return (PlanTimeline){0};}
        int hold=types&&types[i]==3;
        for(int j=0;j<6;j++){
            float at=hold?fmaxf(times[i].start,times[i].end-0.22f):times[i].start;
            if(j==3)at=times[i].start-bad_delay;
            // 157: Drag expires after 0.10s; Flick after 1.75 * Perfect.
            // Tap/Hold use the active Good threshold. These are not one delay.
            if(j==1){float delay=miss_delay;if(types&&types[i]==2)delay=0.10f;if(types&&types[i]==4)delay=1.75f*(2.f*good_delay-miss_delay);at=times[i].start+delay;}
            if(plan_is_good(j)&&(!types||types[i]==1||hold)){
                float head=times[i].start+(plan_good_early(j,i,seed)?-good_delay:good_delay);
                at=hold?fmaxf(head,times[i].end-0.22f):head;
            }
            t.events[t.length++]=(PlanEvent){at,i,j};
        }
    }
    qsort(t.events,(size_t)t.length,sizeof(PlanEvent),compare_events);return t;
}
static inline PlanTimeline plan_timeline_seed(const PlanTiming *times,const unsigned char *types,int n,float good_delay,float miss_delay,uint32_t seed){return plan_timeline_windows(times,types,n,good_delay,miss_delay,(miss_delay+.22f)*.5f,seed);}
static inline PlanTimeline plan_timeline(const PlanTiming *times,const unsigned char *types,int n,float good_delay,float miss_delay){return plan_timeline_seed(times,types,n,good_delay,miss_delay,0);}
static inline uint32_t plan_random(uint32_t *s){*s^=*s<<13;*s^=*s>>17;*s^=*s<<5;return *s;}
static inline void estimate_plan(const unsigned char *plan,int n,PlanEstimate *e){
    memset(e,0,sizeof(*e));if(n<1)return;int combo=0;
    for(int i=1;i<=n;i++){
        if(plan[i]==0)e->perfect++;else if(plan[i]==1)e->miss++;else if(plan_is_good(plan[i]))e->good++;else e->bad++;
        if(plan[i]==1||plan[i]==3)combo=0;else{combo++;if(combo>e->max_combo)e->max_combo=combo;}
    }
    // Audited constants from ScoreControl.Update, content version 157.
    float percent=((float)e->perfect+0.65f*(float)e->good)/(float)n;
    e->score=(int)(900000.f*percent+100000.f*(float)e->max_combo/(float)n+0.5f);
    e->accuracy=(int)(10000.f*percent+0.5f);
}
static inline void estimate_timed(const unsigned char *plan,int n,PlanEstimate *e,const PlanTimeline *timeline){
    estimate_plan(plan,n,e);if(!timeline||n<1)return;
    int combo=0;if(timeline->events)e->max_combo=0;
    for(int k=0;timeline->events&&k<timeline->length;k++){
        PlanEvent event=timeline->events[k];if(plan[event.note]!=event.judgment)continue;
        if(event.judgment==1||event.judgment==3)combo=0;
        else if(++combo>e->max_combo)e->max_combo=combo;
    }
    int denominator=timeline->denominator>0?timeline->denominator:n;
    float percent=((float)e->perfect+0.65f*(float)e->good)/(float)denominator;
    e->accuracy=(int)(10000.f*percent+0.5f);
    e->score=(int)((timeline->challenge?1000000.f:900000.f)*percent+(timeline->challenge?0.f:100000.f*(float)e->max_combo/(float)denominator)+0.5f);
}
static inline int eligible(const NoteRule *r,const unsigned char *types,int i){return r->note_type==0||(types&&types[i]==r->note_type);}
static inline void apply_rule(const NoteRule *r,int n,uint32_t *rng,unsigned char *plan,unsigned char *fixed,const unsigned char *types){
    if(r->judgment<0||r->judgment>5||r->position<1||r->position>6)return;
    int first=r->start,last=r->end;
    if(r->position==1)last=first;
    if(r->position==5)last=first+r->count-1;
    if(r->position==6){first=1;last=n;}
    if(r->position==3){for(int k=0;k<r->length&&k<MAX_INDICES;k++){int i=r->indices[k];if(i>=1&&i<=n&&!fixed[i]&&eligible(r,types,i)){plan[i]=(unsigned char)r->judgment;fixed[i]=1;}}}
    else if(r->position==4){
        int count=0;for(int i=1;i<=n;i++)if(!fixed[i]&&eligible(r,types,i))count++;
        int k=r->count;if(k>count)k=count;
        // Select without replacement in one pass, avoiding a second 80 KB stack array.
        for(int i=1;i<=n&&k>0;i++)if(!fixed[i]&&eligible(r,types,i)){if(plan_random(rng)%(uint32_t)count<(uint32_t)k){plan[i]=(unsigned char)r->judgment;fixed[i]=1;k--;}count--;}
    }else{
        if(first<1)first=1;
        if(last>n)last=n;
        for(int i=first;i<=last;i++)if(!fixed[i]&&eligible(r,types,i)){plan[i]=(unsigned char)r->judgment;fixed[i]=1;}
    }
}
static inline int make_profile_timed(const ControlConfig *c,int n,uint32_t seed,unsigned char *plan,PlanEstimate *e,const unsigned char *types,const PlanTimeline *timeline){
    memset(e,0,sizeof(*e));if(n<1||n>MAX_NOTES)return 0;
    unsigned char fixed[MAX_NOTES+1]={0},base[MAX_NOTES+1],trial[MAX_NOTES+1],best[MAX_NOTES+1];
    memset(plan,0,(size_t)n+1);uint32_t rng=seed?seed:0x6d2b79f5u;
    for(int k=0;k<c->rule_count&&k<MAX_RULES;k++)apply_rule(&c->rules[k],n,&rng,plan,fixed,types);
    estimate_timed(plan,n,e,timeline);
    if(c->mode==1||c->mode==2){
        memcpy(base,plan,(size_t)n+1);memcpy(best,base,(size_t)n+1);
        PlanEstimate best_e=*e;int target=c->mode==1?c->target:c->accuracy;
        int best_error=abs((c->mode==1?e->score:e->accuracy)-target),best_changes=0;
        int order[MAX_NOTES],free_count=0;
        for(int i=1;i<=n;i++)if(!fixed[i])order[free_count++]=i;
        for(int i=0;i<free_count;i++){int j=i+(int)(plan_random(&rng)%(uint32_t)(free_count-i));int t=order[i];order[i]=order[j];order[j]=t;}
        for(int pass=0;pass<160;pass++){
            int misses=pass<40?pass:(int)((int64_t)(pass-40)*free_count/119);
            if(misses>free_count)continue;
            memcpy(trial,base,(size_t)n+1);
            for(int i=0;i<misses;i++)trial[order[i]]=1;
            PlanEstimate current;estimate_timed(trial,n,&current,timeline);
            int denominator=timeline&&timeline->denominator>0?timeline->denominator:n;
            double raw=c->mode==1?((double)current.score-target)*denominator/(timeline&&timeline->challenge?350000.0:315000.0):((double)current.accuracy-target)*denominator/3500.0;
            int center=(int)(raw+0.5);if(center<0)center=0;if(center>free_count-misses)center=free_count-misses;
            for(int delta=-2;delta<=2;delta++){
                int wanted=center+delta,goods=0;if(wanted<0)continue;
                for(int i=misses;i<free_count;i++){
                    int id=order[i];trial[id]=0;
                    // Preserve synthetic Drag/Flick Good as requested.
                    if(goods<wanted){trial[id]=2;goods++;}
                }
                estimate_timed(trial,n,&current,timeline);
                int error=abs((c->mode==1?current.score:current.accuracy)-target),changes=misses+goods;
                if(error<best_error||(error==best_error&&changes<best_changes)){
                    best_error=error;best_changes=changes;best_e=current;memcpy(best,trial,(size_t)n+1);
                }
            }
        }
        memcpy(plan,best,(size_t)n+1);*e=best_e;
    }
    return e->good+e->bad+e->miss;
}
static inline int make_profile_typed(const ControlConfig *c,int n,uint32_t seed,unsigned char *plan,PlanEstimate *e,const unsigned char *types){return make_profile_timed(c,n,seed,plan,e,types,NULL);}
static inline int make_profile(const ControlConfig *c,int n,uint32_t seed,unsigned char *plan,PlanEstimate *e){return make_profile_typed(c,n,seed,plan,e,NULL);}
#endif
