#!/usr/bin/env python3
# -*- coding: utf-8 -*-
'''browser_search: 在浏览器中打开百度搜索结果页，并可抓取/返回搜索结果。

功能模式:
    python browser_search.py '搜索关键词'
        -> 打开浏览器 + 抓取并返回搜索结果（默认）
    python browser_search.py '搜索关键词' --fetch-only
        -> 只抓取并返回搜索结果，不打开浏览器（适用于无头/服务器环境）
    python browser_search.py '搜索关键词' --no-open
        -> 只生成搜索 URL，不打开浏览器也不抓取结果（历史兼容）

输出: 单行 JSON（ensure_ascii=True，纯 ASCII，任何控制台编码下都不会乱码），
      成功时 success=True；失败时 success=False 并带 error 字段，退出码为 1。
'''
import argparse
import json
import sys
import urllib.parse
import webbrowser

import requests
from bs4 import BeautifulSoup

BAIDU_SEARCH_URL = 'https://www.baidu.com/s?wd={}'
REQUEST_HEADERS = {
    'User-Agent': ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                   'AppleWebKit/537.36 (KHTML, like Gecko) '
                   'Chrome/120.0.0.0 Safari/537.36'),
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
}
REQUEST_TIMEOUT = 15  # 秒
DEFAULT_TOP = 8       # 默认返回的搜索结果条数


def build_url(query):
    return BAIDU_SEARCH_URL.format(urllib.parse.quote(query, safe=''))


def fetch_page(url):
    '''请求百度搜索结果页，返回网页文本。百度会做风控校验时抛异常。'''
    resp = requests.get(url, headers=REQUEST_HEADERS, timeout=REQUEST_TIMEOUT)
    resp.raise_for_status()
    resp.encoding = resp.apparent_encoding or 'utf-8'
    return resp.text


def extract_results(html, top=DEFAULT_TOP):
    '''从百度搜索结果页 HTML 中解析出标题/链接/摘要列表。'''
    soup = BeautifulSoup(html, 'html.parser')
    items = []

    for container in soup.select('div.result, div.c-container'):
        a = container.select_one('h3 a')
        title = a.get_text(strip=True) if a else ''
        link = a.get('href', '') if a else ''
        # 摘要：优先取百度结构化摘要，退化为整段文本去掉标题
        abstract = ''
        c = (container.select_one('div.c-abstract')
             or container.select_one('span.content-right_8Zs40'))
        if c:
            abstract = c.get_text(' ', strip=True)
        else:
            txt = container.get_text(' ', strip=True)
            abstract = txt.replace(title, '').strip()
        if title:
            items.append({'title': title, 'link': link, 'abstract': abstract})

    # 结果个数超限时只保留前 top 条
    return items[:top]


def emit(payload):
    # ensure_ascii=True: 中文转义为 \uXXXX，保证跨平台控制台输出不乱码
    print(json.dumps(payload))


def main():
    parser = argparse.ArgumentParser(description='百度搜索：打开浏览器并/或返回搜索结果')
    parser.add_argument('query', help='搜索关键词')
    parser.add_argument('--fetch-only', action='store_true',
                        help='只抓取并返回搜索结果，不打开浏览器（适用于无头/服务器环境）')
    parser.add_argument('--no-open', action='store_true',
                        help='只输出搜索 URL，不打开浏览器也不抓取结果（历史兼容）')
    parser.add_argument('--top', type=int, default=DEFAULT_TOP,
                        help='返回的搜索结果条数（默认 {}）'.format(DEFAULT_TOP))
    args = parser.parse_args()

    url = build_url(args.query)

    # --no-open：只生成 URL，保持与旧版本行为一致
    if args.no_open:
        emit({'success': True, 'url': url, 'opened': False,
              'results': [],
              'message': '已生成 URL，未打开浏览器也未抓取结果'})
        return

    # 抓取并解析搜索结果
    try:
        html = fetch_page(url)
        results = extract_results(html, top=args.top)
    except Exception as exc:
        emit({'success': False, 'url': url, 'opened': False,
              'results': [],
              'error': '抓取搜索结果失败: {}'.format(exc),
              'hint': '百度可能触发了风控校验。可改用 --no-open 只输出 URL，'
                       '或稍后重试。'})
        sys.exit(1)

    # --fetch-only：只返回结果，不打开浏览器
    if args.fetch_only:
        emit({'success': True, 'url': url, 'opened': False,
              'results': results, 'top': args.top,
              'message': '已抓取到搜索结果（共 {} 条），关键词: {}'.format(
                  len(results), args.query)})
        return

    # 默认：打开浏览器 + 返回结果
    try:
        opened = webbrowser.open(url)
    except Exception as exc:
        emit({'success': False, 'url': url, 'opened': False,
              'results': results,
              'error': '打开浏览器失败: {}'.format(exc)})
        sys.exit(1)

    if opened:
        emit({'success': True, 'url': url, 'opened': True,
              'results': results, 'top': args.top,
              'message': '已在浏览器中打开，并抓取到搜索结果（共 {} 条），关键词: {}'.format(
                  len(results), args.query)})
    else:
        emit({'success': True, 'url': url, 'opened': False,
              'results': results, 'top': args.top,
              'message': '抓取到搜索结果（共 {} 条），但当前环境未能打开浏览器，'
                         '已将结果返回。关键词: {}'.format(len(results), args.query),
              'hint': '如仍需浏览器打开，请让用户手动打开 url，或用 --fetch-only'})


if __name__ == '__main__':
    main()
