#!/bin/sh
# Linux Deploy Component
# (c) Anton Skshidlevsky <meefik@gmail.com>, GPLv3

# 4.0 说明：
# - 新增 USER_GROUPS 配置键，支持 "组名" 或 "用户:组名" 两种写法，默认用户取 USER_NAME。
#   例：USER_GROUPS="aid_inet aid_sdcard_rw aid_graphics"（即 root:aid_inet ...）
# - 保留旧版 PRIVILEGED_USERS="UID:GID" 数字格式作为兼容回退。
# - 组名解析优先查 android_groups 表（保证与 Android 宿主 GID 一致）。
# - 组成员以“用户名”写入（数字 UID 无法被 su/getgrouplist 正确解析）。

do_configure()
{
    msg ":: 正在配置 ${COMPONENT} ... "
    # 设置最小 uid/gid（避开 Android 已占用的 uid 段）
    local login_defs
    login_defs="${CHROOT_DIR}/etc/login.defs"
    if [ ! -e "${login_defs}" ]; then
        touch "${login_defs}"
    fi

    grep -q '^ *UID_MIN' "${login_defs}" || echo "UID_MIN 5000" >>"${login_defs}"
    sed -i 's|^ *UID_MIN.*|UID_MIN 5000|' "${login_defs}"
    grep -q '^ *UID_MAX' "${login_defs}" || echo "UID_MAX 10000" >>"${login_defs}"
    sed -i 's|^ *UID_MAX.*|UID_MAX 10000|' "${login_defs}"

    grep -q '^ *GID_MIN' "${login_defs}" || echo "GID_MIN 5000" >>"${login_defs}"
    sed -i 's|^ *GID_MIN.*|GID_MIN 5000|' "${login_defs}"
    grep -q '^ *GID_MAX' "${login_defs}" || echo "GID_MAX 10000" >>"${login_defs}"
    sed -i 's|^ *GID_MAX.*|GID_MAX 10000|' "${login_defs}"

    # 同步全部 Android 辅助组到容器（组名与 GID 均来自 android_groups 表）
    local aid
    for aid in $(cat "${COMPONENT_DIR}/android_groups")
    do
        local xname=$(echo ${aid} | awk -F: '{print $1}')
        local xid=$(echo ${aid} | awk -F: '{print $2}')
        sed -i "s|^${xname}:.*|${xname}:x:${xid}:|" "${CHROOT_DIR}/etc/group"
        if ! grep -q "^${xname}:" "${CHROOT_DIR}/etc/group"; then
            echo "${xname}:x:${xid}:" >> "${CHROOT_DIR}/etc/group"
        fi
        if ! grep -q "^${xname}:" "${CHROOT_DIR}/etc/passwd"; then
            echo "${xname}:x:${xid}:${xid}::/:/bin/false" >> "${CHROOT_DIR}/etc/passwd"
        fi
    done

    # 4.0：将指定用户加入辅助组（USER_GROUPS）
    local entry
    for entry in ${USER_GROUPS}
    do
        local ug_user ug_group ug_uid ug_gid members
        ug_user="${entry%%:*}"
        if [ "${entry}" = "${ug_user}" ]; then
            # 只写了组名，用户默认取 USER_NAME
            ug_user="${USER_NAME:-root}"
            ug_group="${entry}"
        else
            ug_group="${entry#*:}"
        fi
        # 解析 GID：优先查 android_groups 表，再查容器 /etc/group
        ug_gid=$(awk -F: -v n="${ug_group}" '$1==n {print $2}' "${COMPONENT_DIR}/android_groups" | head -1)
        [ -n "${ug_gid}" ] || ug_gid=$(awk -F: -v n="${ug_group}" '$1==n {print $3}' "${CHROOT_DIR}/etc/group" | head -1)
        if [ -z "${ug_gid}" ]; then
            msg "警告: 辅助组不存在，已跳过: ${ug_group}"
            continue
        fi
        # 确保组在容器 /etc/group 中存在（保留 Android GID）
        if ! grep -q "^${ug_group}:" "${CHROOT_DIR}/etc/group"; then
            echo "${ug_group}:x:${ug_gid}:" >> "${CHROOT_DIR}/etc/group"
        fi
        # 解析用户 UID（默认 USER_NAME）
        ug_uid=$(awk -F: -v n="${ug_user}" '$1==n {print $3}' "${CHROOT_DIR}/etc/passwd" | head -1)
        if [ -z "${ug_uid}" ]; then
            msg "警告: 用户不存在，已跳过: ${ug_user}"
            continue
        fi
        # 以“用户名”加入组成员（数字 UID 无法被 su/getgrouplist 解析）
        members=$(grep "^${ug_group}:" "${CHROOT_DIR}/etc/group" | head -1 | cut -d: -f4)
        case ",${members}," in
        *",${ug_user},"*)
            # 已加入，跳过
            :
        ;;
        *)
            if [ -z "${members}" ]; then
                sed -i "s|^\(${ug_group}:.*:\)$|\1${ug_user}|" "${CHROOT_DIR}/etc/group"
            else
                sed -i "s|^\(${ug_group}:.*\)$|\1,${ug_user}|" "${CHROOT_DIR}/etc/group"
            fi
        ;;
        esac
    done

    # 兼容旧版数字格式 PRIVILEGED_USERS="UID:GID ..."（仅在未使用 USER_GROUPS 时生效）
    if [ -n "${PRIVILEGED_USERS}" ] && [ -z "${USER_GROUPS}" ]; then
        local usr
        for usr in ${PRIVILEGED_USERS}
        do
            local uid=${usr%%:*}
            local gid=${usr##*:}
            sed -i "s|^\(${gid}:.*:[^:]+\)$|\1,${uid}|" "${CHROOT_DIR}/etc/group"
            sed -i "s|^\(${gid}:.*:\)$|\1${uid}|" "${CHROOT_DIR}/etc/group"
        done
    fi
    return 0
}

do_help()
{
cat <<EOF
   --user-groups="${USER_GROUPS}"
     用户要加入的辅助组，空格分隔；支持 "组名" 或 "用户:组名" 两种写法。
     默认: aid_inet aid_sdcard_rw aid_graphics（即 root:aid_inet root:aid_sdcard_rw root:aid_graphics）

   --privileged-users="${PRIVILEGED_USERS}"
     旧版数字格式（兼容）: UID:GID 空格分隔，将 UID 追加到 GID 组。

EOF
}
