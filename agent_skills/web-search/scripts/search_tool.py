"""
网络搜索工具 - 支持Tavily和DuckDuckGo搜索
优先使用Tavily API，如果不可用则降级到DuckDuckGo HTML搜索
"""
import argparse
import json
import logging
import sys
import os
from typing import Dict, Any, List

# 配置日志输出到 stderr
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    stream=sys.stderr
)
logger = logging.getLogger(__name__)
os.environ["TAVILY_API_KEY"] = "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"

def search_with_tavily(query: str, num_results: int = 5, api_key: str = None) -> Dict[str, Any]:
    """使用Tavily搜索API
    
    Args:
        query: 搜索关键词
        num_results: 返回结果数量
        api_key: Tavily API密钥
    
    Returns:
        搜索结果字典
    """
    try:
        from langchain_community.tools.tavily_search.tool import TavilySearchResults
        
        # 设置API密钥
        if api_key:
            os.environ["TAVILY_API_KEY"] = api_key
        elif "TAVILY_API_KEY" not in os.environ:
            return {
                "success": False,
                "error": "缺少Tavily API密钥，请设置环境变量TAVILY_API_KEY或通过--api-key参数提供"
            }
        
        logger.info(f"使用Tavily搜索: {query}")
        
        # 创建搜索工具
        search = TavilySearchResults(max_results=num_results)
        
        # 执行搜索
        results = search.invoke(query)
        
        # 解析结果
        if isinstance(results, str):
            # 如果返回的是字符串，尝试解析JSON
            try:
                results_data = json.loads(results)
            except:
                results_data = [{"content": results}]
        elif isinstance(results, list):
            results_data = results
        else:
            results_data = [results]
        
        # 格式化结果
        formatted_results = []
        for i, result in enumerate(results_data[:num_results], 1):
            if isinstance(result, dict):
                formatted_results.append({
                    "rank": i,
                    "title": result.get("title", "N/A"),
                    "content": result.get("content", result.get("snippet", "N/A")),
                    "url": result.get("url", "N/A")
                })
            else:
                formatted_results.append({
                    "rank": i,
                    "title": "N/A",
                    "content": str(result),
                    "url": "N/A"
                })
        
        return {
            "success": True,
            "engine": "tavily",
            "query": query,
            "total_results": len(formatted_results),
            "results": formatted_results
        }
    except ImportError as e:
        logger.warning(f"Tavily库未安装: {str(e)}")
        return {
            "success": False,
            "error": f"Tavily库未安装，请运行: pip install langchain-community tavily-python",
            "fallback_available": True
        }
    except Exception as e:
        logger.error(f"Tavily搜索失败: {str(e)}")
        return {
            "success": False,
            "error": f"Tavily搜索失败: {str(e)}",
            "fallback_available": True
        }


def search_with_duckduckgo(query: str, num_results: int = 5) -> Dict[str, Any]:
    try:
        import requests
        from bs4 import BeautifulSoup
        import time

        logger.info(f"使用DuckDuckGo搜索: {query}")

        headers = {
            'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36'
        }

        search_url = f"https://html.duckduckgo.com/html/?q={query}"

        # 关键改动：重试 3 次 + 超时拉到 30 秒
        for attempt in range(3):
            try:
                response = requests.get(search_url, headers=headers, timeout=30)
                response.raise_for_status()
                break
            except Exception as e:
                logger.warning(f"第 {attempt+1} 次尝试失败: {e}")
                time.sleep(2)
        else:
            raise Exception("3 次重试全部失败")

        soup = BeautifulSoup(response.text, 'html.parser')
        results = []

        for i, result in enumerate(soup.select('.result')[:num_results], 1):
            title_elem = result.select_one('.result__title')
            snippet_elem = result.select_one('.result__snippet')
            url_elem = result.select_one('.result__url')

            if title_elem and snippet_elem:
                results.append({
                    "rank": i,
                    "title": title_elem.get_text(strip=True),
                    "content": snippet_elem.get_text(strip=True),
                    "url": url_elem.get('href') if url_elem else 'N/A'
                })

        return {
            "success": True,
            "engine": "duckduckgo",
            "query": query,
            "total_results": len(results),
            "results": results
        }

    except Exception as e:
        logger.error(f"DuckDuckGo搜索失败: {str(e)}")
        return {
            "success": False,
            "error": f"DuckDuckGo搜索失败: {str(e)}"
        }


def search_web(query: str, num_results: int = 5, api_key: str = None, 
               prefer_engine: str = "tavily") -> Dict[str, Any]:
    """网络搜索 - 优先使用Tavily，失败则降级到DuckDuckGo
    
    Args:
        query: 搜索关键词
        num_results: 返回结果数量
        api_key: Tavily API密钥
        prefer_engine: 首选搜索引擎 (tavily, duckduckgo)
    
    Returns:
        搜索结果字典
    """
    logger.info(f"开始搜索: {query}, 首选引擎: {prefer_engine}")
    
    # 如果首选DuckDuckGo，直接使用
    if prefer_engine == "duckduckgo":
        return search_with_duckduckgo(query, num_results)
    
    # 尝试Tavily搜索
    tavily_result = search_with_tavily(query, num_results, api_key)
    
    if tavily_result["success"]:
        return tavily_result
    
    # Tavily失败，检查是否可以降级
    if tavily_result.get("fallback_available", False):
        logger.info("Tavily搜索失败，降级到DuckDuckGo搜索")
        return search_with_duckduckgo(query, num_results)
    
    # 无法降级，返回错误
    return tavily_result


def main():
    parser = argparse.ArgumentParser(description="网络搜索工具 - 支持Tavily和DuckDuckGo搜索")
    parser.add_argument("query", help="搜索关键词")
    parser.add_argument("--num-results", type=int, default=5, help="返回结果数量（默认5个）")
    parser.add_argument("--api-key", help="Tavily API密钥（可选，也可通过环境变量TAVILY_API_KEY设置）")
    parser.add_argument("--engine", choices=["tavily", "duckduckgo"], default="tavily",
                       help="首选搜索引擎（默认tavily）")
    parser.add_argument("--output", choices=["json", "text"], default="json",
                       help="输出格式（默认json）")
    
    args = parser.parse_args()
    
    # 执行搜索
    result = search_web(args.query, args.num_results, args.api_key, args.engine)
    
    # 输出结果
    if args.output == "json":
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        # 文本格式输出
        if result["success"]:
            print(f"\n🔍 搜索 '{result['query']}' 的结果 (引擎: {result['engine']}):\n")
            for item in result["results"]:
                print(f"{item['rank']}. {item['title']}")
                print(f"   {item['content']}")
                print(f"   链接: {item['url']}\n")
        else:
            print(f"\n❌ 搜索失败: {result['error']}\n")


if __name__ == "__main__":
    main()
