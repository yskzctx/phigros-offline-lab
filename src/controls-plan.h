#ifndef CONTROLS_PLAN_H
#define CONTROLS_PLAN_H
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#define MAX_NOTES 20000
#define MAX_INDICES 1024
#define MAX_RULES 16
#define MAX_CONFIG_WORDS (1+MAX_RULES*(6+MAX_INDICES))
typedef struct {int judgment,position,start,end,count,length;int indices[MAX_INDICES];} NoteRule;
typedef struct {int mode,target,accuracy,rule_count;NoteRule rules[MAX_RULES];} ControlConfig;
typedef struct {int score,accuracy,perfect,good,bad,miss,max_combo;} PlanEstimate;
static inline uint32_t plan_random(uint32_t *s){*s^=*s<<13;*s^=*s>>17;*s^=*s<<5;return *s;}
static inline void estimate_plan(const unsigned char *plan,int n,PlanEstimate *e){
    memset(e,0,sizeof(*e));int combo=0;
    for(int i=1;i<=n;i++){
        if(plan[i]==0)e->perfect++;else if(plan[i]==1)e->miss++;else if(plan[i]==2)e->good++;else e->bad++;
        if(plan[i]==1||plan[i]==3)combo=0;else{combo++;if(combo>e->max_combo)e->max_combo=combo;}
    }
    // Audited constants from ScoreControl.Update, content version 157.
    float percent=((float)e->perfect+0.65f*(float)e->good)/(float)n;
    e->score=(int)(900000.f*percent+100000.f*(float)e->max_combo/(float)n+0.5f);
    e->accuracy=(int)(10000.f*percent+0.5f);
}
static inline void apply_rule(const NoteRule *r,int n,uint32_t *rng,unsigned char *plan,unsigned char *fixed){
    if(r->judgment<1||r->judgment>3||r->position<1||r->position>6)return;
    int first=r->start,last=r->end;
    if(r->position==1)last=first;
    if(r->position==5)last=first+r->count-1;
    if(r->position==6){first=1;last=n;}
    if(r->position==3){for(int k=0;k<r->length&&k<MAX_INDICES;k++){int i=r->indices[k];if(i>=1&&i<=n&&!fixed[i]){plan[i]=(unsigned char)r->judgment;fixed[i]=1;}}}
    else if(r->position==4){
        int count=0;for(int i=1;i<=n;i++)if(!fixed[i])count++;
        int k=r->count;if(k>count)k=count;
        // Select without replacement in one pass, avoiding a second 80 KB stack array.
        for(int i=1;i<=n&&k>0;i++)if(!fixed[i]){if(plan_random(rng)%(uint32_t)count<(uint32_t)k){plan[i]=(unsigned char)r->judgment;fixed[i]=1;k--;}count--;}
    }else{
        if(first<1)first=1;
        if(last>n)last=n;
        for(int i=first;i<=last;i++)if(!fixed[i]){plan[i]=(unsigned char)r->judgment;fixed[i]=1;}
    }
}
static inline int make_profile(const ControlConfig *c,int n,uint32_t seed,unsigned char *plan,PlanEstimate *e){
    memset(e,0,sizeof(*e));if(n<1||n>MAX_NOTES)return 0;
    unsigned char fixed[MAX_NOTES+1]={0},base[MAX_NOTES+1],trial[MAX_NOTES+1],best[MAX_NOTES+1];
    memset(plan,0,(size_t)n+1);uint32_t rng=seed?seed:0x6d2b79f5u;
    for(int k=0;k<c->rule_count&&k<MAX_RULES;k++)apply_rule(&c->rules[k],n,&rng,plan,fixed);
    estimate_plan(plan,n,e);
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
            PlanEstimate current;estimate_plan(trial,n,&current);
            double raw=c->mode==1?((double)current.score-target)*n/315000.0:((double)current.accuracy-target)*n/3500.0;
            int goods=(int)(raw+0.5);if(goods<0)goods=0;if(goods>free_count-misses)goods=free_count-misses;
            for(int i=0;i<goods;i++)trial[order[misses+i]]=2;
            estimate_plan(trial,n,&current);
            int error=abs((c->mode==1?current.score:current.accuracy)-target),changes=misses+goods;
            if(error<best_error||(error==best_error&&changes>best_changes)){
                best_error=error;best_changes=changes;best_e=current;memcpy(best,trial,(size_t)n+1);
            }
        }
        memcpy(plan,best,(size_t)n+1);*e=best_e;
    }
    return e->good+e->bad+e->miss;
}
#endif
