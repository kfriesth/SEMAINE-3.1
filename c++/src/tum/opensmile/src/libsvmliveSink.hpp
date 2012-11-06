/*F******************************************************************************
*
* openSMILE - open Speech and Music Interpretation by Large-space Extraction
*       the open-source Munich Audio Feature Extraction Toolkit
* Copyright (C) 2008-2009  Florian Eyben, Martin Woellmer, Bjoern Schuller
*
*
* Institute for Human-Machine Communication
* Technische Universitaet Muenchen (TUM)
* D-80333 Munich, Germany
*
*
* If you use openSMILE or any code from openSMILE in your research work,
* you are kindly asked to acknowledge the use of openSMILE in your publications.
* See the file CITING.txt for details.
*
* This program is free software; you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation; either version 2 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program; if not, write to the Free Software
* Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*
******************************************************************************E*/


/*  openSMILE component:

LibSVM live classifier/regressor

inherit this class, if you want custom handling of classifier output values..

*/


#ifndef __CLIBSVMLIVESINK_HPP
#define __CLIBSVMLIVESINK_HPP

#include <smileCommon.hpp>
#include <smileComponent.hpp>
#include <dataSink.hpp>
#include <svm/svm.h>

#define COMPONENT_DESCRIPTION_CLIBSVMLIVESINK "This component classifies data from dataMemory 'on-the-fly' using the LibSVM library. Loading of ASCII and binary LibSVM models is supported, as well as application of LibSVM scale files and openSMILE feature selection lists."
#define COMPONENT_NAME_CLIBSVMLIVESINK "cLibsvmLiveSink"

class DLLEXPORT sClassifierResults {
public:
  sClassifierResults() : res(NULL), prob(NULL), resnameA(NULL), resnameB(NULL), nFilled(0), nResults(0) {}

  ~sClassifierResults() 
  {
    if (res != NULL) free(res);
    if (prob != NULL) free(prob);
    //int i;
    if (resnameA != NULL) {
      //for (i=0; i<nResults; i++) if (resnameA[i] != NULL) free(resnameA[i]);
      free(resnameA);
    }
    if (resnameB != NULL) {
      //for (i=0; i<nResults; i++) if (resnameB[i] != NULL) free(resnameB[i]);
      free(resnameB);
    }

  }

  void alloc() {
    if (nResults > 0) {
      res = (double *)calloc(1,sizeof(double)*nResults);
      prob = (double *)calloc(1,sizeof(double)*nResults*nTargets);
      resnameA = (const char **)calloc(1,sizeof(const char *)*nResults);
      resnameB = (const char **)calloc(1,sizeof(const char *)*nResults);
    }
  }

  int nFilled;
  int nResults; // N
  int nTargets; // C = number of classes or regression outputs
  double *res;  // dim: 1xN
  double *prob; // dim: CxN
  const char ** resnameA; // custom name1 of result
  const char ** resnameB; // custom name2 of result (can be NULL)
} ;

/**************************************************************************/
/*********              LibSVM   addon:   scale functions  ****************/
/**************************************************************************/

struct svm_scale {
  int max_index;
  int y_scaling;
  double lower,upper;
  double y_lower,y_upper;
  double y_min,y_max;
  double *feature_max;
  double *feature_min;
};
struct svm_scale * svm_load_scale(const char* restore_filename);
void svm_destroy_scale(struct svm_scale *scale);
void svm_apply_scale(struct svm_scale *scale, struct svm_node * x);

/**************************************************************************/

typedef struct{
  long n;
  char **names;
} sOutputSelectionStr;  // list of names of selected features (more flexible..)
typedef sOutputSelectionStr *pOutputSelectionStr;

typedef struct{
  long nFull;   // n here is the full length of the "unselected" feature vector
  long nSel;
  long *map;
  long *enabled;  // flag : 0 or 1  when feature is disabled/enabled respectively
} sOutputSelectionIdx;  
typedef sOutputSelectionIdx *pOutputSelectionIdx;

typedef struct{
  const char *fselection;
  int fselType; long Nsel;
  sOutputSelectionStr outputSelStr;
  sOutputSelectionIdx outputSelIdx;
} sFselectionData;

#undef class


class DLLEXPORT lsvmDataFrame 
{ public:

  int isLast; // indicates the last frame for batchMode (a result message will be sent)
  long dataSize; // size of input data struct allocated memory in bytes
  svm_node *x; // the input data
  int modelchoice;
  long long tick;
  long frameIdx;
  double time;
  double res;
  double *probEstim; // a copy of the probEstim memory... must be freed
  int nClasses;
  double dur;
  int isFinal;

  lsvmDataFrame() : probEstim(NULL), dur(0.0), isFinal(1), isLast(0) {}

  lsvmDataFrame(svm_node *_x, long _dataSize, int _modelchoice, long long _tick, long _frameIdx, double _time, double _res, int doProbEstim, int _nClasses, double _dur=0.0, int _isFinal=1) :
    dataSize(_dataSize), tick(_tick), modelchoice(_modelchoice), frameIdx(_frameIdx), time(_time), res(_res), nClasses(_nClasses), dur(_dur), isFinal(_isFinal), isLast(0)
  {
    // copy input data
    if (_x!=NULL) {
      x = (svm_node*)malloc(dataSize);
      memcpy(x,_x,dataSize);
    } else {
      x = NULL;
    }
    // copy probEstim, if not NULL
    if (doProbEstim) {
      probEstim = (double *) malloc(nClasses*sizeof(double));
    } else {
      probEstim = NULL;
    }
    //  memcpy(probEstim,_probEstim,nClasses*sizeof(double));
  }

  void setLast() { isLast = 1; }

  ~lsvmDataFrame() { 
    if (x != NULL) free(x);
    if (probEstim != NULL) free(probEstim); 
  }
};


// STL includes for the queue
#include <queue>

// a queue of data frames
typedef std::queue<lsvmDataFrame *> lsvmDataFrameQueue;

// a model wrapper containing, the model itself, the scaling, the feature selection and the class labels, etc.
class svmModelWrapper {
public:
  svmModelWrapper(int pred=0) : 
      model(NULL), labels(NULL),
      alienScale(0), scale(NULL),
      alienClassnames(0), classNames(NULL),
      alienFselection(0), fselection(NULL),
      templ(NULL), predictProbability(pred),
      modelFile(NULL), scaleFile(NULL), fselectionFile(NULL), classesFile(NULL),
      modelResultName(NULL), nIgnoreEndSelection(0)
  {}

  ~svmModelWrapper() {
    int i;
    if (model != NULL) svm_destroy_model(model);
    if (labels != NULL) free(labels);
    //if (probEstimates != NULL) free(probEstimates);

    if ((!alienScale)&&(scale != NULL)) svm_destroy_scale(scale);
    if ((!alienClassnames)&&(classNames != NULL)) {
      for (i=0; i<nClassNames; i++) { if (classNames[i] != NULL) free(classNames[i]); }
      free(classNames);
    }
    if ((!alienFselection)&&(fselection != NULL)) {
      if (fselection->outputSelIdx.enabled != NULL) { 
        free(fselection->outputSelIdx.enabled);
      }
      if (fselection->outputSelIdx.map != NULL) { 
        free(fselection->outputSelIdx.map);
      }
      int n;
      if (fselection->outputSelStr.names != NULL) {
        for(n=0; n<fselection->outputSelStr.n; n++) {
          if (fselection->outputSelStr.names[n] != NULL) free(fselection->outputSelStr.names[n]);
        }                             
        free( fselection->outputSelStr.names );
      }
      free(fselection);
    }
  }

  void setTemplate(svmModelWrapper *t) {
    templ = t;
  }

  int loadClasses( const char *file, char *** names );
  int loadSelection( const char *selFile, sFselectionData **fselections );
  int load(); // load everything (model, scale, ...)


  const char *modelResultName;
  const char *modelFile;
  const char *scaleFile;
  const char *fselectionFile;
  const char *classesFile;
  svmModelWrapper *templ;  

  struct svm_model * model;
  int nClasses, svmType, predictProbability;
  //double * probEstimates;
  int *labels;
  
  int alienScale; // a "1" indicates that we don't have our own scale object and use the 0'th object for all models
  struct svm_scale * scale; 
  int alienClassnames; // a "1" indicates that we don't have our own class name list and use the 0'th object for all models
  char ** classNames;
  int nClassNames;
  int nIgnoreEndSelection; // number of elements to ignore from end of selection list
  int noVerify;

  int alienFselection; // a "1" indicates that we don't have our own class name list and use the 0'th object for all models
  sFselectionData *fselection;
};


class DLLEXPORT cLibsvmLiveSink : public cDataSink {
private:
  int sendResult;
  int predictProbability;
  int classifierThreadBusy;

  int forceScale;
  const char * resultRecp;
  const char * resultMessageName;
  //const char **fselection;//, *classes;
  //const char *scalefile, *modelfile;;
  char **modelarray;
  char **classesarray;
  char **scalearray;

  sClassifierResults resCache; // for batchMode

  svmModelWrapper *models;
  //struct svm_model** model;
  //struct svm_scale** scale;
  //int nClasses, svmType;
  //double *probEstimates;
  
  //int *labels;
  int nModels;
  int nScales, nFselections, nClassFiles;
  int currentModel;
  int multiModelMode;
  int batchMode;
  int singlePreprocessMultiModel;

  //long nCls;
  //char ** classNames;
  //int classnames_run;

  lsvmDataFrameQueue dataFrameQue;
  int threadRunning;
  int loadModelBg;
  int modelLoaded;
  int useThread;
  int threadQueSize;

  int abortLater;
  int noVerify;
  int nIgnoreEndSelection;

  //int buildEnabledSelFromNames(long N, const FrameMetaInfo *fmeta, int index);
  int buildEnabledSelFromNames(long N, const FrameMetaInfo *fmeta, sFselectionData *fselection);
  //int loadSelection( const char *selFile, sFselectionData **fsel );
  //int loadClasses( const char *file, char *** names );
  int loadClassifier();

  void processDigestFrame(lsvmDataFrame * f, int modelIdx);
  void digestFrame(lsvmDataFrame * f, int modelIdx);

  struct svm_node * preprocessFrame(int modelIdx, cVector *vec);

protected:
  SMILECOMPONENT_STATIC_DECL_PR
  int printResult;
  smileCond dataCond;
  smileMutex dataMtx, runningMtx;
  smileThread bgThread;

  virtual void fetchConfig();
  //virtual int myConfigureInstance();
  virtual int myFinaliseInstance();
  virtual int myTick(long long t);
  //
  virtual int processComponentMessage( cComponentMessage *_msg );

  int queFrameToClassify(lsvmDataFrame *fr);

  //virtual void processResult(long long tick, long frameIdx, double time, float res, double *probEstim, int nClasses, double dur=0.0, int isFinal=1);
  virtual void processResult(lsvmDataFrame *f, int modelIdx, int mmm /* multiModelMode */);

public:
  //static sComponentInfo * registerComponent(cConfigManager *_confman);
  //static cSmileComponent * create(const char *_instname);
  SMILECOMPONENT_STATIC_DECL


  cLibsvmLiveSink(const char *_name);
  void classifierThread();

  virtual ~cLibsvmLiveSink();
};




#endif // __CLIBSVMLIVESINK_HPP
