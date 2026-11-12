static void emit_absolute(uint32_t **cursor,unsigned reg,uintptr_t address,bool branch) {
    *(*cursor)++ = 0x58000040u | reg; // ldr Xreg, PC+8
    *(*cursor)++ = branch ? (0xd61f0000u | reg<<5) : 0x14000003u;
    memcpy(*cursor,&address,8); *cursor+=2;
}
static int install(const struct HookSpec *spec,void *replacement,void **original) {
    unsigned char *target=(unsigned char *)(image+spec->rva);
    if (memcmp(target,spec->prefix,16)!=0) return -1;
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    uint32_t *trampoline=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    if (trampoline==MAP_FAILED) return -2;
    uint32_t *out=trampoline;
    for (int i=0;i<4;i++) {
        uint32_t instruction;memcpy(&instruction,target+i*4,4);
        if ((instruction & 0x9f000000u)==0x90000000u) {
            // Relocate ADRP to the same absolute original-image page.
            int64_t immediate=((instruction>>29)&3u) | (((instruction>>5)&0x7ffffu)<<2);
            if (immediate & (1<<20)) immediate-=1<<21;
            uintptr_t value=((uintptr_t)(target+i*4)&~(uintptr_t)4095)+(immediate*4096);
            emit_absolute(&out,instruction&31u,value,false);
        } else {
            // Only the audited non-PC-relative prologue instructions are accepted.
            if (spec->relocate_mask & (1u<<i)) { munmap(trampoline,page);return -3; }
            *out++=instruction;
        }
    }
    emit_absolute(&out,17,(uintptr_t)(target+16),true);
    __builtin___clear_cache((char *)trampoline,(char *)out);
    if (mprotect(trampoline,page,PROT_READ|PROT_EXEC)!=0) { munmap(trampoline,page);return -4; }
    *original=trampoline;
    uintptr_t first=(uintptr_t)target&~(page-1);
    size_t length=((uintptr_t)target+16-first+page-1)&~(page-1);
    if (mprotect((void *)first,length,PROT_READ|PROT_WRITE|PROT_EXEC)!=0) return -5;
    uint32_t jump[4],*cursor=jump;emit_absolute(&cursor,17,(uintptr_t)replacement,true);
    memcpy(target,jump,16);
    __builtin___clear_cache((char *)target,(char *)target+16);
    mprotect((void *)first,length,PROT_READ|PROT_EXEC);
    return 0;
}
static int find_image(struct dl_phdr_info *info,size_t size,void *data) {
    (void)size;(void)data;
    const char *name=strrchr(info->dlpi_name,'/');name=name?name+1:info->dlpi_name;
    if (strcmp(name,"libil2cpp.so")==0) { image=info->dlpi_addr;return 1; }
    return 0;
}
