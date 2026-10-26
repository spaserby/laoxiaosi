"""Export the measured development-set ablation as SVG and PNG (optional matplotlib)."""
import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--main", type=Path, required=True)
    parser.add_argument("--stress", type=Path, required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--font", type=Path)
    args = parser.parse_args()
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import font_manager
    import numpy as np
    if args.font:
        font_manager.fontManager.addfont(str(args.font))
        plt.rcParams["font.family"] = font_manager.FontProperties(fname=str(args.font)).get_name()
    plt.rcParams.update({"svg.fonttype": "none", "font.size": 11, "axes.spines.top": False,
                         "axes.spines.right": False, "axes.spines.left": False, "axes.titleweight": "bold"})
    primary = json.loads((args.main / "dev-results.json").read_text(encoding="utf-8"))["summary"]
    stress = {row["config"]: row for row in json.loads((args.stress / "dev-results.json").read_text(encoding="utf-8"))["summary"]}
    labels = [row["config"] for row in primary]
    x = np.arange(len(labels))
    fig, axes = plt.subplots(1, 2, figsize=(12.8, 6.8), dpi=100, gridspec_kw={"width_ratios": [1.3, 1]})
    fig.patch.set_facecolor("#F8FAFC")
    for ax in axes:
        ax.set_facecolor("#F8FAFC")
        ax.set_axisbelow(True)
        ax.grid(axis="y", color="#E2E8F0", linewidth=0.8)
        ax.tick_params(length=0)
    a = axes[0].bar(x - .22, [r["allRequiredRate"] * 100 for r in primary], .32, color="#1E3A8A", label="8000 token 主实验")
    b = axes[0].bar(x + .22, [stress[r["config"]]["allRequiredRate"] * 100 for r in primary], .32, color="#D97706", label="2000 token 压力诊断")
    for bars, rows in [(a, primary), (b, [stress[label] for label in labels])]:
        axes[0].bar_label(bars, labels=[str(r["allRequired"]) + "/" + str(r["answerable"]) for r in rows], fontsize=9, padding=4)
    axes[0].set_ylim(0, 119)
    axes[0].set_ylabel("全部必要证据句覆盖率（%）")
    axes[0].set_title("同一批调参题，预算缩小时才出现差异", pad=28, loc="left", fontsize=13)
    axes[0].legend(loc="upper left", bbox_to_anchor=(0, -.26), frameon=False, fontsize=10)
    axes[0].set_xticks(x, labels, rotation=32, ha="right")
    bars = axes[1].bar(x, [r["index"]["children"] for r in primary], color=["#1E3A8A" if r["config"] == "baseline" else "#60A5FA" for r in primary], width=.65)
    axes[1].bar_label(bars, padding=4, fontsize=10)
    axes[1].set_ylim(0, max(r["index"]["children"] for r in primary) * 1.18)
    axes[1].set_ylabel("索引子块数（7份合成资料）")
    axes[1].set_title("更小子块显著增加索引规模", pad=28, loc="left", fontsize=13)
    axes[1].set_xticks(x, labels, rotation=32, ha="right")
    fig.suptitle("附件 RAG 分块实测：质量与索引规模分开比较", x=.055, y=.97, ha="left", fontsize=20, fontweight="bold", color="#1E3A8A")
    fig.text(.055, .895, "调参集12道有答案题；6组配置只改一个因素；固定原文证据标注；保留集另行验证", fontsize=11, color="#475569")
    fig.text(.055, .035, "注：这是合成资料上的证据覆盖代理指标，不代表语义忠实度或法律风险检出率。", fontsize=10, color="#64748B")
    fig.subplots_adjust(left=.065, right=.98, bottom=.27, top=.78, wspace=.28)
    args.output_directory.mkdir(parents=True, exist_ok=True)
    for extension in ("svg", "png"):
        fig.savefig(args.output_directory / ("65-attachment-quality-ablation." + extension), dpi=100, metadata={"Creator": "attachment quality evaluation"})
    plt.close(fig)
    print("Measured chart exported as SVG and PNG")


if __name__ == "__main__":
    main()
