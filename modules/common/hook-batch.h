#include <stddef.h>
typedef int (*LabHook)(void*,void*,void**);
typedef int (*LabUnhook)(void*);
typedef struct {void *targets[32];size_t count;LabHook hook;LabUnhook unhook;} LabBatch;
static int batch_install(LabBatch *b,void *target,void *replace,void **original){
 if(!b->hook||!b->unhook||b->count>=32)return -1;
 int status=b->hook(target,replace,original);
 if(status==0)b->targets[b->count++]=target;
 return status;
}
static int batch_rollback(LabBatch *b){int failures=0;while(b->count){if(b->unhook(b->targets[--b->count])!=0)failures++;}return failures;}
