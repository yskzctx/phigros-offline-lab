#ifndef CONTROLS_PLAN_H
#define CONTROLS_PLAN_H
#include <stdint.h>
#include <string.h>
#define MAX_NOTES 20000
#define MAX_INDICES 1024
typedef struct {
    int target,judgment,position,start,end,count,length;
    int indices[MAX_INDICES];
} ControlConfig;
static inline int has_intervention(const ControlConfig *c) {
    return c->judgment>=1 && c->judgment<=3 && c->position>=1 && c->position<=6;
}
static inline uint32_t plan_random(uint32_t *s) {
    *s^=*s<<13;*s^=*s>>17;*s^=*s<<5;return *s;
}
static inline int make_plan(const ControlConfig *c,int n,uint32_t seed,unsigned char *selected) {
    if(n<1 || n>MAX_NOTES)return 0;
    memset(selected,0,(size_t)n+1);
    if(!has_intervention(c))return 0;
    int first=c->start,last=c->end;
    if(c->position==1)last=first;
    if(c->position==5)last=first+c->count-1;
    if(c->position==6){first=1;last=n;}
    if(c->position==1 || c->position==2 || c->position==5 || c->position==6) {
        if(first<1)first=1;
        if(last>n)last=n;
        for(int i=first;i<=last;i++)selected[i]=1;
    } else if(c->position==3) {
        for(int i=0;i<c->length && i<MAX_INDICES;i++)
            if(c->indices[i]>=1 && c->indices[i]<=n)selected[c->indices[i]]=1;
    } else if(c->position==4) {
        // Partial Fisher-Yates: unique, bounded, no retry loop when K >= N.
        int order[MAX_NOTES];for(int i=0;i<n;i++)order[i]=i+1;
        int k=c->count;if(k>n)k=n;if(k<0)k=0;
        uint32_t rng=seed?seed:0x6d2b79f5u;
        for(int i=0;i<k;i++) {
            int j=i+(int)(plan_random(&rng)%(uint32_t)(n-i));
            int t=order[i];order[i]=order[j];order[j]=t;selected[order[i]]=1;
        }
    }
    int total=0;for(int i=1;i<=n;i++)total+=selected[i]!=0;return total;
}
#endif
