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

LibSVM file output

*/


#ifndef __CLIBSVMSINK_HPP
#define __CLIBSVMSINK_HPP

#include <smileCommon.hpp>
#include <dataSink.hpp>

#define COMPONENT_DESCRIPTION_CLIBSVMSINK "This component writes data to a text file in LibSVM feature file format. For the 'on-the-fly' classification component see 'cLibsvmliveSink'."
#define COMPONENT_NAME_CLIBSVMSINK "cLibsvmSink"


#undef class
class DLLEXPORT cLibsvmSink : public cDataSink {
  private:
    FILE * filehandle;
    const char *filename;
    int lag;
    int append, timestamp;
    const char *instanceBase, *instanceName;
    
    int targetNumAll;
    int nClasses;
    long nInst;
    long inr;
    char **classname;
    int *target;

    int getClassIndex(const char *_name)
    {
      int i;
      if (_name == NULL) return -1;
      if (classname == NULL) return -1;
      for (i=0; i<nClasses; i++) {
        if ((classname[i]!=NULL)&&(!strcmp(_name,classname[i]))) return i;
      }
      return -1;
    }

  protected:
    SMILECOMPONENT_STATIC_DECL_PR
    
    virtual void fetchConfig();
    //virtual int myConfigureInstance();
    virtual int myFinaliseInstance();
    virtual int myTick(long long t);


  public:
    //static sComponentInfo * registerComponent(cConfigManager *_confman);
    //static cSmileComponent * create(const char *_instname);
    SMILECOMPONENT_STATIC_DECL
    
    cLibsvmSink(const char *_name);

    virtual ~cLibsvmSink();
};




#endif // __CLIBSVMSINK_HPP
