/*
 * Copyright (c) 2001-2008
 *     DecisionSoft Limited. All rights reserved.
 * Copyright (c) 2004-2008
 *     Oracle. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * $Id$
 */

#ifndef _NODECOMPARISON_HPP
#define _NODECOMPARISON_HPP

#include <xqilla/framework/XQillaExport.hpp>

#include <xqilla/ast/XQOperator.hpp>
#include <xqilla/runtime/SingleResult.hpp>

/** NodeComparison operator function*/
class XQILLA_API NodeComparison : public XQOperator
{
public:
  static const XMLCh name[];

  NodeComparison(const VectorOfASTNodes &args, XPath2MemoryManager* memMgr);
  
  ASTNode* staticResolution(StaticContext *context);
  virtual ASTNode *staticTypingImpl(StaticContext *context);
  Result createResult(DynamicContext* context, int flags=0) const;

private:
  class NodeComparisonResult : public SingleResult
  {
  public:
    NodeComparisonResult(const NodeComparison *op);

    Item::Ptr getSingleResult(DynamicContext *context) const;
  private:
    const NodeComparison *_op;
  };
};

#endif
