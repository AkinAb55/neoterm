#ifndef _UK_LINUX_PATH_H
#define _UK_LINUX_PATH_H
#ifndef _UK_STRUCT_PATH_DEFINED
#define _UK_STRUCT_PATH_DEFINED
struct path { struct vfsmount *mnt; struct dentry *dentry; };
#endif
struct vfsmount; struct dentry;
void path_put(const struct path *path);
int path_get(const struct path *path);
#endif
