from pathlib import Path

from openpyxl import Workbook, load_workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.table import Table, TableStyleInfo


OUTPUT_DIR = Path("outputs/ai_toolbench_purchase_list")
OUTPUT_FILE = OUTPUT_DIR / "一起互动AI工具台上线采购清单.xlsx"


PURCHASE_ROWS = [
    ["服务器与基础环境", "应用服务器", "云服务器或客户内网服务器", "建议 4C8G 起，正式按并发和工具数量扩容；Linux 系统", "1", "台", "必须", "客户采购/提供", "待报价", "部署前", "用于前端、后端和静态工具入口", "https://ecs-buy.aliyun.com/"],
    ["服务器与基础环境", "数据库服务器", "PostgreSQL 实例", "客户现有 PostgreSQL 或阿里云 RDS PostgreSQL；需提供库名、账号、密码和网络白名单", "1", "套", "必须", "客户采购/提供", "待报价", "部署前", "正式环境按客户 PostgreSQL 标准部署", "https://rdsbuy.console.aliyun.com/"],
    ["服务器与基础环境", "数据库容量扩容", "运行记录、AI 调用日志、操作日志均存 PostgreSQL", "不单独购买日志服务；需按长期保存请求参数、返回结果、Token、成本估算数据库容量", "按实际", "GB", "必须", "客户采购/提供", "待报价", "部署前", "日志直接入库，需纳入 PostgreSQL 容量和备份评估", "https://cn.aliyun.com/product/rds/postgresql"],
    ["服务器与基础环境", "文件存储空间", "本地磁盘或内网对象存储", "用于上传文件、生成图片、报告附件、工具输出", "按实际", "GB/TB", "必须", "客户采购/提供", "待报价", "部署前", "需确认备份策略和访问权限", "https://cn.aliyun.com/product/oss"],
    ["网络与安全", "域名或内网访问地址", "平台访问域名/内网地址", "如 ai-tools.xxx.com 或内网 IP", "1", "个", "必须", "客户提供", "待确认", "部署前", "需确认公网/内网访问策略", "https://wanwang.aliyun.com/domain"],
    ["网络与安全", "HTTPS 证书", "SSL 证书", "如使用 HTTPS 访问需准备证书和私钥", "1", "套", "建议", "客户采购/提供", "待报价", "部署前", "内网可按客户安全要求决定", "https://cn.aliyun.com/product/cas"],
    ["AI 能力", "AI API 中转服务", "统一大模型 API 网关/中转地址", "文本、图片模型调用统一从平台后端转发", "1", "套", "必须", "客户采购/提供", "待报价", "联调前", "工具不得直接调用模型供应商", "https://cn.aliyun.com/product/api-gateway"],
    ["AI 能力", "文本模型额度", "文本模型 Token 额度", "用于市场情报、消费者洞察、竞店分析等工具", "按月", "Token", "必须", "客户采购/提供", "待报价", "联调前", "需提供模型名和计费规则", "https://cn.aliyun.com/product/bailian"],
    ["AI 能力", "图片模型额度", "图片生成额度", "用于推广图制作、建模渲染等图片类工具", "按月", "张/次", "必须", "客户采购/提供", "待报价", "联调前", "需提供模型名、尺寸、单价", "https://cn.aliyun.com/product/bailian"],
    ["AI 能力", "备用模型额度", "备用文本/图片模型", "主模型失败或限流时兜底", "按需", "套", "可选", "客户采购/提供", "待报价", "上线后", "一期可预留配置", "https://cn.aliyun.com/product/bailian"],
    ["安全与运维", "备份服务", "数据库和文件备份", "建议每日备份，保留周期由客户确认", "1", "项", "建议", "客户采购/提供", "待报价", "上线前", "防止运行记录和附件丢失", "https://cn.aliyun.com/product/dbs"],
    ["安全与运维", "监控告警", "服务器、接口、磁盘、模型调用监控", "用于服务异常、磁盘不足、AI 网关失败告警", "1", "套", "建议", "客户采购/提供", "待报价", "上线后", "可复用客户现有监控", "https://cn.aliyun.com/product/cms"],
]

def setup_sheet(ws, title):
    ws.sheet_view.showGridLines = False
    ws.freeze_panes = "A4"
    ws["A1"] = title
    ws["A1"].font = Font(name="Arial", size=18, bold=True, color="FFFFFF")
    ws["A1"].fill = PatternFill("solid", fgColor="5B55EA")
    ws["A1"].alignment = Alignment(horizontal="left", vertical="center")
    ws.merge_cells(start_row=1, start_column=1, end_row=1, end_column=10)
    ws.row_dimensions[1].height = 30


def style_range(ws, min_row, max_row, min_col, max_col):
    thin = Side(style="thin", color="E5E7EB")
    header_fill = PatternFill("solid", fgColor="EEF0FF")
    for row in ws.iter_rows(min_row=min_row, max_row=max_row, min_col=min_col, max_col=max_col):
        for cell in row:
            cell.border = Border(bottom=thin)
            cell.alignment = Alignment(vertical="top", wrap_text=True)
            cell.font = Font(name="Arial", size=10, color="1A1C29")
    for cell in ws[min_row]:
        cell.fill = header_fill
        cell.font = Font(name="Arial", size=10, bold=True, color="1A1C29")
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)


def autosize(ws, widths):
    for idx, width in enumerate(widths, start=1):
        ws.column_dimensions[get_column_letter(idx)].width = width


def build():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    wb = Workbook()
    wb.remove(wb.active)

    detail = wb.create_sheet("采购明细")
    setup_sheet(detail, "一起互动 AI 工具台上线采购清单")
    detail["A2"] = "说明：金额列先留待报价/待确认，客户确认部署环境和模型服务后再补充预算。"
    detail["A2"].font = Font(name="Arial", size=10, color="6B7280")
    detail.merge_cells(start_row=2, start_column=1, end_row=2, end_column=11)
    headers = ["分类", "采购/准备项", "规格/内容", "建议配置或说明", "数量", "单位", "优先级", "责任方", "预算/费用", "需要时间", "备注", "阿里云购买/产品页"]
    detail.append([])
    detail.append(headers)
    for row in PURCHASE_ROWS:
        detail.append(row)
    style_range(detail, 4, 4 + len(PURCHASE_ROWS), 1, len(headers))
    autosize(detail, [16, 20, 24, 38, 10, 10, 10, 16, 14, 14, 34, 38])
    for row_idx in range(5, 5 + len(PURCHASE_ROWS)):
        cell = detail.cell(row_idx, 12)
        cell.hyperlink = cell.value
        cell.style = "Hyperlink"
    table = Table(displayName="PurchaseList", ref=f"A4:L{4 + len(PURCHASE_ROWS)}")
    table.tableStyleInfo = TableStyleInfo(name="TableStyleMedium2", showFirstColumn=False, showLastColumn=False, showRowStripes=True, showColumnStripes=False)
    detail.add_table(table)
    detail.auto_filter.ref = table.ref

    summary = wb.create_sheet("采购汇总")
    setup_sheet(summary, "采购汇总")
    summary_headers = ["分类", "必须项数量", "建议项数量", "可选项数量", "说明"]
    summary.append([])
    summary.append(summary_headers)
    categories = []
    for row in PURCHASE_ROWS:
        if row[0] not in categories:
            categories.append(row[0])
    start = 4
    for i, category in enumerate(categories, start=start):
        summary.cell(i, 1).value = category
        summary.cell(i, 2).value = f'=COUNTIFS(采购明细!$A$5:$A${4 + len(PURCHASE_ROWS)},$A{i},采购明细!$G$5:$G${4 + len(PURCHASE_ROWS)},"必须")'
        summary.cell(i, 3).value = f'=COUNTIFS(采购明细!$A$5:$A${4 + len(PURCHASE_ROWS)},$A{i},采购明细!$G$5:$G${4 + len(PURCHASE_ROWS)},"建议")'
        summary.cell(i, 4).value = f'=COUNTIFS(采购明细!$A$5:$A${4 + len(PURCHASE_ROWS)},$A{i},采购明细!$G$5:$G${4 + len(PURCHASE_ROWS)},"可选")'
        summary.cell(i, 5).value = "按采购明细逐项确认责任方和预算"
    total_row = start + len(categories)
    summary.cell(total_row, 1).value = "合计"
    summary.cell(total_row, 2).value = f"=SUM(B{start}:B{total_row-1})"
    summary.cell(total_row, 3).value = f"=SUM(C{start}:C{total_row-1})"
    summary.cell(total_row, 4).value = f"=SUM(D{start}:D{total_row-1})"
    summary.cell(total_row, 5).value = "优先处理必须项"
    style_range(summary, 4, total_row, 1, 5)
    for cell in summary[total_row]:
        cell.font = Font(name="Arial", size=10, bold=True, color="1A1C29")
        cell.fill = PatternFill("solid", fgColor="F3F4F6")
    autosize(summary, [18, 14, 14, 14, 42])

    optional = wb.create_sheet("可选增强")
    setup_sheet(optional, "可选增强项")
    optional.append([])
    optional.append(["增强项", "用途", "是否一期必须", "建议", "阿里云购买/产品页"])
    optional_rows = [
        ["独立对象存储", "大规模图片、附件和报告存储", "否", "文件量大时建议购买", "https://cn.aliyun.com/product/oss"],
        ["监控告警平台", "接口、磁盘、AI 网关失败告警", "否", "如客户已有监控可复用", "https://cn.aliyun.com/product/cms"],
        ["备用模型服务", "主模型限流或异常时兜底", "否", "上线后根据稳定性决定", "https://cn.aliyun.com/product/bailian"],
        ["自动备份服务", "数据库和文件定期备份", "建议", "正式运行建议配置", "https://cn.aliyun.com/product/dbs"],
        ["WAF/访问审计", "增强公网安全和访问审计", "按部署方式", "公网访问时建议配置", "https://cn.aliyun.com/product/waf"],
    ]
    for row in optional_rows:
        optional.append(row)
    style_range(optional, 4, 4 + len(optional_rows), 1, 5)
    autosize(optional, [24, 42, 16, 36, 38])
    for row_idx in range(5, 5 + len(optional_rows)):
        cell = optional.cell(row_idx, 5)
        cell.hyperlink = cell.value
        cell.style = "Hyperlink"

    for ws in wb.worksheets:
        ws.freeze_panes = "A4"
        for row in ws.iter_rows():
            for cell in row:
                cell.alignment = Alignment(vertical="top", wrap_text=True)

    wb.save(OUTPUT_FILE)


if __name__ == "__main__":
    build()
    loaded = load_workbook(OUTPUT_FILE, data_only=False)
    assert "采购明细" in loaded.sheetnames
    assert loaded["采购明细"]["A1"].value
    print(OUTPUT_FILE)
