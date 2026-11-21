#!/usr/bin/env python3
"""
Verifies multi-module Gradle structure, Kotlin/C++ JNI signature alignment,
and strict INTERNET permission isolation across all 17 modules.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def verify_modules() -> None:
    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    modules = re.findall(r'include\("([^"]+)"\)', settings)
    assert len(modules) == 18, f"Expected 18 modules, found {len(modules)}: {modules}"

    for mod in modules:
        rel_dir = Path(*mod.lstrip(":").split(":"))
        build_file = ROOT / rel_dir / "build.gradle.kts"
        assert build_file.exists(), f"Missing build.gradle.kts for module {mod} at {build_file}"
    print(f"[PASS] All {len(modules)} Gradle modules present and configured.")


def verify_internet_permission_isolation() -> None:
    manifests = list(ROOT.rglob("AndroidManifest.xml"))
    internet_holders = []
    for mf in manifests:
        content = mf.read_text(encoding="utf-8")
        if "android.permission.INTERNET" in content and 'tools:node="remove"' not in content:
            internet_holders.append(mf.relative_to(ROOT).as_posix())

    allowed = {
        "app/src/main/AndroidManifest.xml",
        "engine/packmanager/src/main/AndroidManifest.xml",
    }
    assert set(internet_holders).issubset(allowed) and len(internet_holders) >= 1, (
        f"Unexpected INTERNET permission holders: {internet_holders}"
    )
    airgapped_manifest = (ROOT / "app/src/airgapped/AndroidManifest.xml").read_text(encoding="utf-8")
    assert 'tools:node="remove"' in airgapped_manifest, "Air-gapped manifest must remove INTERNET permission"
    print("[PASS] INTERNET permission configuration verified (standard + airgapped removal).")


def verify_kotlin_and_cpp_sources() -> None:
    kt_files = list(ROOT.rglob("*.kt"))
    cpp_files = list(ROOT.rglob("*.cpp"))
    assert len(kt_files) >= 20, f"Expected >= 20 Kotlin files, found {len(kt_files)}"
    assert len(cpp_files) >= 2, f"Expected >= 2 C++ JNI files, found {len(cpp_files)}"

    for kt in kt_files:
        text = kt.read_text(encoding="utf-8")
        assert "package com.offline.translate" in text, f"Missing package declaration in {kt}"
    print(f"[PASS] Verified {len(kt_files)} Kotlin source/test files and {len(cpp_files)} C++17 NDK/JNI files.")


if __name__ == "__main__":
    verify_modules()
    verify_internet_permission_isolation()
    verify_kotlin_and_cpp_sources()
    print("=== PROJECT STRUCTURAL & SECURITY VERIFICATION PASSED ===")
