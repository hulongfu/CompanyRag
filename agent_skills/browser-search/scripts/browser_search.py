#!/usr/bin/env python3
# -*- coding: utf-8 -*-
'''browser_search: 在浏览器中打开百度搜索页面。

用法:
    python browser_search.py '搜索关键词'
    python browser_search.py '搜索关键词' --no-open   # 只生成 URL，不打开浏览器

输出: 单行 JSON（ensure_ascii=True，纯 ASCII，任何控制台编码下都不会乱码）
'''
import argparse
import json
import sys
import urllib.parse
import webbrowser

BAIDU_SEARCH_URL = 'https://www.baidu.com/s?wd={}'


def build_url(query):
    return BAIDU_SEARCH_URL.format(urllib.parse.quote(query, safe=''))


def emit(payload):
    # ensure_ascii=True: 中文转义为 \uXXXX，保证跨平台控制台输出不乱码
    print(json.dumps(payload))


def main():
    parser = argparse.ArgumentParser(description='在浏览器中打开百度搜索')
    parser.add_argument('query', help='搜索关键词')
    parser.add_argument('--no-open', action='store_true',
                        help='只输出搜索 URL，不打开浏览器（适用于无头环境）')
    args = parser.parse_args()

    url = build_url(args.query)

    if args.no_open:
        emit({'success': True, 'url': url, 'opened': False,
              'message': '已生成 URL，未打开浏览器'})
        return

    try:
        opened = webbrowser.open(url)
    except Exception as exc:
        emit({'success': False, 'url': url,
              'error': '打开浏览器失败: {}'.format(exc)})
        sys.exit(1)

    if opened:
        emit({'success': True, 'url': url, 'opened': True,
              'message': '已在浏览器中打开百度搜索结果页，关键词: {}'.format(args.query)})
    else:
        emit({'success': False, 'url': url, 'opened': False,
              'error': '未找到可用浏览器（当前环境可能为无头/服务器环境）',
              'hint': '请让用户手动打开 url，或改用 --no-open 只生成 URL'})
        sys.exit(1)


if __name__ == '__main__':
    main()
