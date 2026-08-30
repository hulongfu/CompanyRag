"""
文件管理技能 - 统一的文件操作工具
支持：读取、写入、创建、删除、移动、复制、列表、搜索等操作
"""

# ========== 编码设置：解决 Windows 控制台 GBK 编码问题 ==========
# 设置标准输出为标准 UTF-8，避免输出 Unicode 字符（如 ✅）时编码失败
import io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')
# ================================================================

import argparse
import json
import logging
import sys
import os
import shutil
import stat
from pathlib import Path
from typing import Dict, Any, Optional, List
from datetime import datetime

# 配置日志输出到 stderr
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    stream=sys.stderr
)
logger = logging.getLogger(__name__)


def resolve_path(base_folder: str, relative_path: str = "") -> Path:
    """解析路径
    
    Args:
        base_folder: 基础文件夹路径
        relative_path: 相对路径
    
    Returns:
        解析后的完整路径
    """
    # 处理特殊关键字
    if base_folder == "shared":
        base_path = Path.cwd() / "shared"
    elif base_folder == "desktop":
        base_path = Path.home() / "Desktop"
    elif base_folder == "documents":
        base_path = Path.home() / "Documents"
    elif base_folder == "downloads":
        base_path = Path.home() / "Downloads"
    elif base_folder.startswith("/") or (len(base_folder) > 1 and base_folder[1] == ":"):
        # 绝对路径
        base_path = Path(base_folder)
    else:
        # 相对于当前工作目录
        base_path = Path.cwd() / base_folder
    
    # 拼接相对路径
    if relative_path:
        full_path = (base_path / relative_path).resolve()
    else:
        full_path = base_path.resolve()
    
    return full_path


def read_file(file_path: str, base_folder: str = "shared", encoding: str = "utf-8") -> Dict[str, Any]:
    """读取文件内容
    
    Args:
        file_path: 文件路径
        base_folder: 基础文件夹
        encoding: 文件编码
    
    Returns:
        包含文件内容的字典
    """
    try:
        full_path = resolve_path(base_folder, file_path)
        
        if not full_path.exists():
            return {
                "success": False,
                "error": f"文件不存在: {file_path}"
            }
        
        if not full_path.is_file():
            return {
                "success": False,
                "error": f"不是文件: {file_path}"
            }
        
        logger.info(f"读取文件: {full_path}")
        
        with open(full_path, 'r', encoding=encoding) as f:
            content = f.read()
        
        return {
            "success": True,
            "file_path": str(full_path),
            "content": content,
            "size": len(content),
            "encoding": encoding
        }
    except UnicodeDecodeError:
        # 尝试其他编码
        try:
            with open(full_path, 'r', encoding='gbk') as f:
                content = f.read()
            return {
                "success": True,
                "file_path": str(full_path),
                "content": content,
                "size": len(content),
                "encoding": "gbk"
            }
        except Exception as e:
            return {
                "success": False,
                "error": f"编码错误: {str(e)}"
            }
    except Exception as e:
        logger.error(f"读取文件失败: {str(e)}")
        return {
            "success": False,
            "error": f"读取文件失败: {str(e)}"
        }


def write_file(file_path: str, content: str, base_folder: str = "shared",
               encoding: str = "utf-8", overwrite: bool = True) -> Dict[str, Any]:
    """写入文件内容
    
    Args:
        file_path: 文件路径
        content: 文件内容
        base_folder: 基础文件夹
        encoding: 文件编码
        overwrite: 是否覆盖现有文件
    
    Returns:
        操作结果字典
    """
    try:
        full_path = resolve_path(base_folder, file_path)
        
        if full_path.exists() and not overwrite:
            return {
                "success": False,
                "error": f"文件已存在: {file_path}"
            }
        
        # 确保父目录存在
        full_path.parent.mkdir(parents=True, exist_ok=True)
        
        logger.info(f"写入文件: {full_path}")
        
        with open(full_path, 'w', encoding=encoding) as f:
            f.write(content)
        
        return {
            "success": True,
            "file_path": str(full_path),
            "size": len(content),
            "message": f"文件已写入: {file_path}"
        }
    except Exception as e:
        logger.error(f"写入文件失败: {str(e)}")
        return {
            "success": False,
            "error": f"写入文件失败: {str(e)}"
        }


def create_folder(folder_path: str, base_folder: str = "shared", parents: bool = True) -> Dict[str, Any]:
    """创建文件夹
    
    Args:
        folder_path: 文件夹路径
        base_folder: 基础文件夹
        parents: 是否创建父目录
    
    Returns:
        操作结果字典
    """
    try:
        full_path = resolve_path(base_folder, folder_path)
        
        if full_path.exists():
            if full_path.is_dir():
                return {
                    "success": True,
                    "file_path": str(full_path),
                    "message": f"文件夹已存在: {folder_path}"
                }
            else:
                return {
                    "success": False,
                    "error": f"与现有文件重名: {folder_path}"
                }
        
        logger.info(f"创建文件夹: {full_path}")
        full_path.mkdir(parents=parents, exist_ok=True)
        
        return {
            "success": True,
            "file_path": str(full_path),
            "message": f"文件夹已创建: {folder_path}"
        }
    except Exception as e:
        logger.error(f"创建文件夹失败: {str(e)}")
        return {
            "success": False,
            "error": f"创建文件夹失败: {str(e)}"
        }


def delete_file(file_path: str, base_folder: str = "shared", force: bool = False) -> Dict[str, Any]:
    """删除文件
    
    Args:
        file_path: 文件路径
        base_folder: 基础文件夹
        force: 是否强制删除
    
    Returns:
        操作结果字典
    """
    try:
        full_path = resolve_path(base_folder, file_path)
        
        if not full_path.exists():
            return {
                "success": False,
                "error": f"文件不存在: {file_path}"
            }
        
        if not full_path.is_file():
            return {
                "success": False,
                "error": f"不是文件: {file_path}"
            }
        
        logger.info(f"删除文件: {full_path}")
        
        if force:
            full_path.chmod(stat.S_IWRITE)
        full_path.unlink()
        
        return {
            "success": True,
            "message": f"文件已删除: {file_path}"
        }
    except Exception as e:
        logger.error(f"删除文件失败: {str(e)}")
        return {
            "success": False,
            "error": f"删除文件失败: {str(e)}"
        }


def delete_folder(folder_path: str, base_folder: str = "shared",
                  recursive: bool = True, force: bool = False) -> Dict[str, Any]:
    """删除文件夹
    
    Args:
        folder_path: 文件夹路径
        base_folder: 基础文件夹
        recursive: 是否递归删除（非空文件夹）
        force: 是否强制删除
    
    Returns:
        操作结果字典
    """
    try:
        full_path = resolve_path(base_folder, folder_path)
        
        if not full_path.exists():
            return {
                "success": False,
                "error": f"文件夹不存在: {folder_path}"
            }
        
        if not full_path.is_dir():
            return {
                "success": False,
                "error": f"不是文件夹: {folder_path}"
            }
        
        logger.info(f"删除文件夹: {full_path}")
        
        if force:
            for item in full_path.rglob('*'):
                item.chmod(stat.S_IWRITE)
        
        shutil.rmtree(full_path)
        
        return {
            "success": True,
            "message": f"文件夹已删除: {folder_path}"
        }
    except Exception as e:
        logger.error(f"删除文件夹失败: {str(e)}")
        return {
            "success": False,
            "error": f"删除文件夹失败: {str(e)}"
        }


def move_item(source_path: str, target_path: str, base_folder: str = "shared") -> Dict[str, Any]:
    """移动或重命名文件/文件夹
    
    Args:
        source_path: 源路径
        target_path: 目标路径
        base_folder: 基础文件夹
    
    Returns:
        操作结果字典
    """
    try:
        source_full = resolve_path(base_folder, source_path)
        target_full = resolve_path(base_folder, target_path)
        
        if not source_full.exists():
            return {
                "success": False,
                "error": f"源路径不存在: {source_path}"
            }
        
        logger.info(f"移动: {source_full} -> {target_full}")
        
        shutil.move(str(source_full), str(target_full))
        
        return {
            "success": True,
            "message": f"已移动: {source_path} -> {target_path}"
        }
    except Exception as e:
        logger.error(f"移动失败: {str(e)}")
        return {
            "success": False,
            "error": f"移动失败: {str(e)}"
        }


def copy_file(source_path: str, target_path: str, base_folder: str = "shared") -> Dict[str, Any]:
    """复制文件
    
    Args:
        source_path: 源文件路径
        target_path: 目标文件路径
        base_folder: 基础文件夹
    
    Returns:
        操作结果字典
    """
    try:
        source_full = resolve_path(base_folder, source_path)
        target_full = resolve_path(base_folder, target_path)
        
        if not source_full.exists():
            return {
                "success": False,
                "error": f"源文件不存在: {source_path}"
            }
        
        if not source_full.is_file():
            return {
                "success": False,
                "error": f"源路径不是文件: {source_path}"
            }
        
        logger.info(f"复制: {source_full} -> {target_full}")
        
        # 确保目标目录存在
        target_full.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(str(source_full), str(target_full))
        
        return {
            "success": True,
            "message": f"已复制: {source_path} -> {target_path}"
        }
    except Exception as e:
        logger.error(f"复制失败: {str(e)}")
        return {
            "success": False,
            "error": f"复制失败: {str(e)}"
        }


def list_files(folder_path: str = "", base_folder: str = "shared",
               pattern: str = "*", recursive: bool = False) -> Dict[str, Any]:
    """列出文件夹中的文件和目录
    
    Args:
        folder_path: 文件夹路径
        base_folder: 基础文件夹
        pattern: 文件名匹配模式
        recursive: 是否递归列出
    
    Returns:
        包含文件列表的字典
    """
    try:
        full_path = resolve_path(base_folder, folder_path)
        
        if not full_path.exists():
            return {
                "success": False,
                "error": f"文件夹不存在: {folder_path}"
            }
        
        if not full_path.is_dir():
            return {
                "success": False,
                "error": f"不是文件夹: {folder_path}"
            }
        
        logger.info(f"列出文件: {full_path}")
        
        if recursive:
            items = list(full_path.rglob(pattern))
        else:
            items = list(full_path.glob(pattern))
        
        files = []
        directories = []
        
        for item in items:
            if item.is_file():
                stat_info = item.stat()
                files.append({
                    "name": item.name,
                    "path": str(item.relative_to(full_path)),
                    "size": stat_info.st_size,
                    "modified": datetime.fromtimestamp(stat_info.st_mtime).isoformat()
                })
            elif item.is_dir():
                directories.append({
                    "name": item.name,
                    "path": str(item.relative_to(full_path))
                })
        
        return {
            "success": True,
            "folder_path": str(full_path),
            "files": files,
            "directories": directories,
            "total_files": len(files),
            "total_directories": len(directories)
        }
    except Exception as e:
        logger.error(f"列出文件失败: {str(e)}")
        return {
            "success": False,
            "error": f"列出文件失败: {str(e)}"
        }


def file_info(file_path: str, base_folder: str = "shared") -> Dict[str, Any]:
    """获取文件/文件夹详细信息
    
    Args:
        file_path: 文件路径
        base_folder: 基础文件夹
    
    Returns:
        包含文件信息的字典
    """
    try:
        full_path = resolve_path(base_folder, file_path)
        
        if not full_path.exists():
            return {
                "success": False,
                "error": f"文件不存在: {file_path}"
            }
        
        logger.info(f"获取文件信息: {full_path}")
        
        stat_info = full_path.stat()
        
        info = {
            "success": True,
            "path": str(full_path),
            "name": full_path.name,
            "type": "directory" if full_path.is_dir() else "file",
            "size": stat_info.st_size,
            "created": datetime.fromtimestamp(stat_info.st_ctime).isoformat(),
            "modified": datetime.fromtimestamp(stat_info.st_mtime).isoformat(),
            "accessed": datetime.fromtimestamp(stat_info.st_atime).isoformat(),
        }
        
        if full_path.is_file():
            info["extension"] = full_path.suffix
            info["parent"] = str(full_path.parent)
        
        return info
    except Exception as e:
        logger.error(f"获取文件信息失败: {str(e)}")
        return {
            "success": False,
            "error": f"获取文件信息失败: {str(e)}"
        }


def search_files(search_pattern: str, base_folder: str = "shared") -> Dict[str, Any]:
    """搜索文件
    
    Args:
        search_pattern: 搜索模式（如 *.py, test_*.txt）
        base_folder: 搜索的文件夹
    
    Returns:
        包含搜索结果的字典
    """
    try:
        full_path = resolve_path(base_folder, "")
        
        if not full_path.exists():
            return {
                "success": False,
                "error": f"文件夹不存在: {base_folder}"
            }
        
        logger.info(f"搜索文件: {search_pattern} in {full_path}")
        
        matches = list(full_path.rglob(search_pattern))
        
        results = []
        for match in matches:
            if match.is_file():
                stat_info = match.stat()
                results.append({
                    "path": str(match.relative_to(full_path)),
                    "full_path": str(match),
                    "size": stat_info.st_size,
                    "modified": datetime.fromtimestamp(stat_info.st_mtime).isoformat()
                })
        
        return {
            "success": True,
            "search_pattern": search_pattern,
            "base_folder": str(full_path),
            "results": results,
            "total_matches": len(results)
        }
    except Exception as e:
        logger.error(f"搜索文件失败: {str(e)}")
        return {
            "success": False,
            "error": f"搜索文件失败: {str(e)}"
        }


def main():
    parser = argparse.ArgumentParser(description="文件管理技能 - 统一的文件操作工具")
    
    # 操作类型
    parser.add_argument("operation", choices=[
        "read", "write", "create-folder", "delete-file", "delete-folder",
        "move", "copy", "list", "info", "search"
    ], help="操作类型")
    
    # 通用参数
    parser.add_argument("--file", help="文件路径")
    parser.add_argument("--folder", help="文件夹路径")
    parser.add_argument("--base-folder", default="shared", help="基础文件夹")
    
    # 操作特定参数
    parser.add_argument("--content", help="文件内容（写入操作）")
    parser.add_argument("--encoding", default="utf-8", help="文件编码")
    parser.add_argument("--target", help="目标路径（移动/复制操作）")
    parser.add_argument("--pattern", default="*", help="文件匹配模式")
    parser.add_argument("--recursive", action="store_true", help="递归操作")
    parser.add_argument("--force", action="store_true", help="强制操作")
    parser.add_argument("--overwrite", action="store_true", default=True, help="覆盖现有文件")
    parser.add_argument("--parents", action="store_true", default=True, help="创建父目录")
    
    args = parser.parse_args()
    
    # 执行操作
    if args.operation == "read":
        result = read_file(args.file, args.base_folder, args.encoding)
    elif args.operation == "write":
        if not args.content:
            result = {"success": False, "error": "缺少 --content 参数"}
        else:
            result = write_file(args.file, args.content, args.base_folder, args.encoding, args.overwrite)
    elif args.operation == "create-folder":
        result = create_folder(args.folder, args.base_folder, args.parents)
    elif args.operation == "delete-file":
        result = delete_file(args.file, args.base_folder, args.force)
    elif args.operation == "delete-folder":
        result = delete_folder(args.folder, args.base_folder, args.recursive, args.force)
    elif args.operation == "move":
        if not args.target:
            result = {"success": False, "error": "缺少 --target 参数"}
        else:
            result = move_item(args.file or args.folder, args.target, args.base_folder)
    elif args.operation == "copy":
        if not args.target:
            result = {"success": False, "error": "缺少 --target 参数"}
        else:
            result = copy_file(args.file, args.target, args.base_folder)
    elif args.operation == "list":
        result = list_files(args.folder or "", args.base_folder, args.pattern, args.recursive)
    elif args.operation == "info":
        result = file_info(args.file or args.folder, args.base_folder)
    elif args.operation == "search":
        result = search_files(args.pattern, args.base_folder)
    else:
        result = {"success": False, "error": f"未知操作: {args.operation}"}
    
    # 输出 JSON 结果到 stdout
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
