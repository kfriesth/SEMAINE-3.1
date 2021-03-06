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

#ifndef _FTCONTENT_HPP
#define _FTCONTENT_HPP

#include <xqilla/fulltext/FTOption.hpp>

class XQILLA_API FTContent : public FTOption
{
public:
  enum Type {
    AT_START,
    AT_END,
    ENTIRE_CONTENT
  };

  FTContent(Type type, XPath2MemoryManager *memMgr)
    : FTOption(FTSelection::CONTENT, memMgr), type_(type), arg_(0) {}
  FTContent(FTSelection *arg, Type type, XPath2MemoryManager *memMgr)
    : FTOption(FTSelection::CONTENT, memMgr), type_(type), arg_(arg) {}

  virtual FTSelection *staticResolution(StaticContext *context);
  virtual FTSelection *staticTyping(StaticContext *context, StaticTyper *styper);
  virtual FTSelection *optimize(FTContext *context, bool execute) const;
  virtual AllMatches::Ptr execute(FTContext *ftcontext) const;

  virtual void setArgument(FTSelection *arg) { arg_ = arg; }
  const FTSelection *getArgument() const { return arg_; }
  Type getType() const { return type_; }

private:
  Type type_;
  FTSelection *arg_;
};

class FTContentAtStartMatches : public AllMatches
{
public:
  FTContentAtStartMatches(const LocationInfo *info, const AllMatches::Ptr &arg)
    : AllMatches(info), arg_(arg) {}
  Match::Ptr next(DynamicContext *context);

private:
  AllMatches::Ptr arg_;
};

class FTContentAtEndMatches : public AllMatches
{
public:
  FTContentAtEndMatches(const LocationInfo *info, const AllMatches::Ptr &arg)
    : AllMatches(info), arg_(arg) {}
  Match::Ptr next(DynamicContext *context);

private:
  AllMatches::Ptr arg_;
};

#endif
