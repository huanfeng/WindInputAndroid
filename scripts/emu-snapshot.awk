# emu-test.sh 运行快照：把 source 的用例文件内联进来，并（给了 only 时）只留选中的分组。
#
# 变量：root = 仓库根；only = 逗号分隔的选择（空 = 全部）。选择写法：
#   24        第 24 组及其子组（24、24a…）
#   48c       只要 48c
#   58-61     数字部分在 58–61 之间的各组（含子组）
# 恒保留：第一个分组之前的前置代码、[0] 前置、[14] 收尾（及其后的汇总），
# 以及每个用例文件第一个分组之前的部分（辅助函数）。

function wanted(id,    n, i, tok, lo, hi, num) {
    if (only == "") return 1
    if (id == "0" || id == "14") return 1
    num = id; sub(/[a-z]+$/, "", num)
    n = split(only, toks, ",")
    for (i = 1; i <= n; i++) {
        tok = toks[i]; gsub(/[[:space:]]/, "", tok)
        if (tok == "") continue
        if (tok ~ /^[0-9]+-[0-9]+$/) {
            split(tok, r, "-"); lo = r[1] + 0; hi = r[2] + 0
            if (num + 0 >= lo && num + 0 <= hi) return 1
        } else if (tok ~ /^[0-9]+$/) {
            if (num == tok) return 1
        } else if (id == tok) {
            return 1
        }
    }
    return 0
}

# 输出一行，按当前分组是否选中决定留不留
function emit(line) { if (keep) print line }

function section_id(line,    m) {
    if (match(line, /^section "\[[0-9]+[a-z]*\]/)) {
        m = substr(line, RSTART + 10, RLENGTH - 11)
        return m
    }
    return ""
}

function handle(line,    id) {
    id = section_id(line)
    if (id != "") keep = wanted(id)
    emit(line)
}

BEGIN { keep = 1 }

{
    # source "$ROOT/scripts/emu-cases/xxx.sh" → 内联；文件开头到它第一个分组之前恒保留
    if (match($0, /^source "\$ROOT\/scripts\/emu-cases\/[^"]+\.sh"/)) {
        path = substr($0, RSTART + 14, RLENGTH - 15)
        file = root "/" path
        saved = keep; keep = 1
        print "# ── 内联：" path
        while ((getline l < file) > 0) handle(l)
        close(file)
        # 文件末尾所在分组决定之后主脚本的行留不留：保持文件内最后的状态，直到下一个分组
        next
    }
    handle($0)
}
